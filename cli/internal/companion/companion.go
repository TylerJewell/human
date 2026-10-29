// Package companion serves the companion's human routes (CONTRACT.md, "Companion routes"): every
// registered service's /oversight/* merged into one API, the local reviewer stamped on every write,
// and each write forwarded to the service that owns it.
package companion

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Options configure a Server.
type Options struct {
	// Home holds services/<service>.json, written by each service on startup, and reviewer.json.
	Home string
	// Registry is an overseer registry in Verify's YAML format. Empty asks the first running
	// service for its registry, then reads FallbackRegistry.
	Registry string
	// FallbackRegistry is read when Registry is empty and no running service answers one.
	FallbackRegistry string
	// ConsoleDir is the built console served at /; empty serves none.
	ConsoleDir string
	// ConformanceJar is the conformance suite run by POST /api/companion/human/conformance.
	ConformanceJar string
	// Refresh is how long one discovery of the services is reused.
	Refresh time.Duration
}

// Server is the companion.
type Server struct {
	opts   Options
	probe  *http.Client
	client *http.Client

	mu      sync.Mutex
	found   []service
	foundAt time.Time

	reviewerMu sync.Mutex
}

func New(opts Options) *Server {
	return &Server{
		opts:   opts,
		probe:  &http.Client{Timeout: time.Second},
		client: &http.Client{Timeout: 15 * time.Second},
	}
}

// allowedOrigins are the console dev servers (docs/09-build.md, "Ports").
var allowedOrigins = map[string]bool{"http://localhost:5181": true, "http://localhost:5182": true}

// Handler answers the companion routes and serves the console.
func (s *Server) Handler() http.Handler {
	const api = "/api/companion/human"
	mux := http.NewServeMux()
	route := func(pattern string, h func(http.ResponseWriter, *http.Request) error) {
		mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
			if err := h(w, r); err != nil {
				writeErr(w, err)
			}
		})
	}
	route("GET "+api+"/events", s.events)
	route("GET "+api+"/events/{id}", s.event)
	route("POST "+api+"/events/{id}/{write}", s.eventWrite)
	route("GET "+api+"/summary", s.summary)
	route("GET "+api+"/quality", s.quality)
	route("GET "+api+"/verdicts", s.verdicts)
	route("GET "+api+"/standing", s.listed("/standing", "standing"))
	route("GET "+api+"/halts", s.listed("/halts", "halts"))
	route("GET "+api+"/controls", s.listed("/controls", "controls"))
	route("POST "+api+"/flags", s.controlWrite("/flags"))
	route("POST "+api+"/halts", s.controlWrite("/halts"))
	route("POST "+api+"/contests", s.controlWrite("/contests"))
	route("POST "+api+"/overrides", s.controlWrite("/overrides"))
	route("POST "+api+"/standing/{id}/revoke", s.revoke)
	route("GET "+api+"/registry", s.registryRoute)
	route("GET "+api+"/reviewer", s.getReviewer)
	route("PUT "+api+"/reviewer", s.putReviewer)
	route("GET "+api+"/services", s.servicesRoute)
	route("POST "+api+"/conformance", s.conformance)
	route("GET /api/", func(http.ResponseWriter, *http.Request) error {
		return errorf(http.StatusNotFound, "no such companion route")
	})
	mux.HandleFunc("GET /", s.console)
	return cors(mux)
}

// cors lets the console dev servers call the companion from the browser.
func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if origin := r.Header.Get("Origin"); allowedOrigins[origin] {
			h := w.Header()
			h.Set("Access-Control-Allow-Origin", origin)
			h.Add("Vary", "Origin")
			h.Set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
			h.Set("Access-Control-Allow-Headers", "Content-Type")
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// console serves the built console, answering index.html for any path that is not a file so the
// console's own routes load it.
func (s *Server) console(w http.ResponseWriter, r *http.Request) {
	if s.opts.ConsoleDir == "" {
		writeErr(w, errorf(http.StatusNotFound, "no console is built; pass --console-dir"))
		return
	}
	name := filepath.Join(s.opts.ConsoleDir, filepath.FromSlash(path.Clean("/"+r.URL.Path)))
	if info, err := os.Stat(name); err == nil && !info.IsDir() {
		http.ServeFile(w, r, name)
		return
	}
	index := filepath.Join(s.opts.ConsoleDir, "index.html")
	if _, err := os.Stat(index); err != nil {
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, index)
}

// httpError is an answer the companion writes as {error}.
type httpError struct {
	status int
	msg    string
}

func (e *httpError) Error() string { return e.msg }

func errorf(status int, format string, args ...any) error {
	return &httpError{status: status, msg: fmt.Sprintf(format, args...)}
}

// passed is a service's answer, written to the console unchanged.
type passed struct {
	status int
	body   []byte
	ctype  string
}

func (p *passed) Error() string { return fmt.Sprintf("a service answered %d", p.status) }

func writeErr(w http.ResponseWriter, err error) {
	var he *httpError
	var p *passed
	switch {
	case errors.As(err, &p):
		writePassed(w, p)
	case errors.As(err, &he):
		writeJSON(w, he.status, map[string]string{"error": he.msg})
	default:
		log.Printf("error: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
}

func writePassed(w http.ResponseWriter, p *passed) {
	ctype := p.ctype
	if ctype == "" {
		ctype = "application/json"
	}
	w.Header().Set("Content-Type", ctype)
	w.WriteHeader(p.status)
	w.Write(p.body)
}

func writeJSON(w http.ResponseWriter, status int, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	w.Write(data)
	return nil
}

const maxBody = 1 << 20

// readObject reads a request body that must be a JSON object; an empty body is {}.
func readObject(r *http.Request) (map[string]json.RawMessage, error) {
	data, err := io.ReadAll(io.LimitReader(r.Body, maxBody))
	if err != nil {
		return nil, errorf(http.StatusBadRequest, "cannot read the body: %v", err)
	}
	body := map[string]json.RawMessage{}
	if len(bytes.TrimSpace(data)) == 0 {
		return body, nil
	}
	if err := json.Unmarshal(data, &body); err != nil || body == nil {
		return nil, errorf(http.StatusBadRequest, "the body must be a JSON object")
	}
	return body, nil
}

// text is a string field of a body; "" when it is missing or not a string.
func text(body map[string]json.RawMessage, key string) string {
	var s string
	if json.Unmarshal(body[key], &s) != nil {
		return ""
	}
	return strings.TrimSpace(s)
}
