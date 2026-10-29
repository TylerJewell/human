package companion

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// registration is services/<service>.json, as a service writes it on startup (CONTRACT.md,
// "Registration").
type registration struct {
	Service   string `json:"service"`
	URL       string `json:"url"`
	Oversight string `json:"oversight"`
	Contract  int    `json:"contract"`
	StartedAt string `json:"startedAt"`
}

// service is a registered service and whether it answered GET /oversight/info.
type service struct {
	registration
	up   bool
	info json.RawMessage
}

// base is where the service serves the oversight routes.
func (s service) base() string {
	oversight := strings.Trim(s.Oversight, "/")
	if oversight == "" {
		oversight = "oversight"
	}
	return strings.TrimRight(s.URL, "/") + "/" + oversight
}

// services are the registered services in name order, each probed. One discovery is reused for
// Options.Refresh.
func (s *Server) services() []service {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.found != nil && time.Since(s.foundAt) < s.opts.Refresh {
		return s.found
	}
	files, _ := filepath.Glob(filepath.Join(s.opts.Home, "services", "*.json"))
	found := []service{}
	named := map[string]bool{}
	for _, file := range files {
		var reg registration
		data, err := os.ReadFile(file)
		if err == nil {
			err = json.Unmarshal(data, &reg)
		}
		if err != nil || reg.Service == "" || reg.URL == "" {
			log.Printf("skipping %s: not a service registration", file)
			continue
		}
		if !named[reg.Service] {
			named[reg.Service] = true
			found = append(found, service{registration: reg})
		}
	}
	sort.Slice(found, func(i, j int) bool { return found[i].Service < found[j].Service })
	var wg sync.WaitGroup
	for i := range found {
		wg.Add(1)
		go func(svc *service) {
			defer wg.Done()
			a, err := call(context.Background(), s.probe, http.MethodGet, svc.base()+"/info", nil)
			if err == nil && a.status == http.StatusOK && json.Valid(a.body) {
				svc.up, svc.info = true, a.body
			}
		}(&found[i])
	}
	wg.Wait()
	s.found, s.foundAt = found, time.Now()
	return found
}

// running are the services that answered their probe.
func (s *Server) running() []service {
	var up []service
	for _, svc := range s.services() {
		if svc.up {
			up = append(up, svc)
		}
	}
	return up
}

// answer is a service's response.
type answer struct {
	status int
	body   []byte
	ctype  string
}

func (a answer) passed() *passed { return &passed{status: a.status, body: a.body, ctype: a.ctype} }

// call sends one request; only a request that gets no response is an error.
func call(ctx context.Context, client *http.Client, method, target string, body []byte) (answer, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, target, reader)
	if err != nil {
		return answer{}, err
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := client.Do(req)
	if err != nil {
		return answer{}, err
	}
	defer res.Body.Close()
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return answer{}, err
	}
	return answer{status: res.StatusCode, body: data, ctype: res.Header.Get("Content-Type")}, nil
}

// reply is one service's answer to a read.
type reply struct {
	svc  service
	body []byte
}

// gather reads path from each service at once and answers the successful replies in the
// services' order. A client error other than 404 is the query's fault, so it fails the read with
// that service's answer unchanged; a service that fails otherwise is left out.
func (s *Server) gather(ctx context.Context, svcs []service, path func(service) string) ([]reply, error) {
	answers := make([]answer, len(svcs))
	errs := make([]error, len(svcs))
	var wg sync.WaitGroup
	for i, svc := range svcs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			answers[i], errs[i] = call(ctx, s.client, http.MethodGet, svc.base()+path(svc), nil)
		}()
	}
	wg.Wait()
	var out []reply
	for i, svc := range svcs {
		a := answers[i]
		switch {
		case errs[i] != nil:
			log.Printf("%s did not answer %s: %v", svc.Service, path(svc), errs[i])
		case a.status >= 400 && a.status < 500 && a.status != http.StatusNotFound:
			return nil, a.passed()
		case a.status != http.StatusOK:
			log.Printf("%s answered %s with %d", svc.Service, path(svc), a.status)
		default:
			out = append(out, reply{svc: svc, body: a.body})
		}
	}
	return out, nil
}

// forward posts body to a service and writes its answer unchanged.
func (s *Server) forward(w http.ResponseWriter, ctx context.Context, to service, path string, body map[string]json.RawMessage) error {
	data, err := json.Marshal(body)
	if err != nil {
		return err
	}
	a, err := call(ctx, s.client, http.MethodPost, to.base()+path, data)
	if err != nil {
		return errorf(http.StatusBadGateway, "%s did not answer: %v", to.Service, err)
	}
	writePassed(w, a.passed())
	return nil
}

// serviceView is a registered service as GET /api/companion/human/services lists it.
type serviceView struct {
	Service string          `json:"service"`
	URL     string          `json:"url"`
	Up      bool            `json:"up"`
	Info    json.RawMessage `json:"info"`
}

func (s *Server) servicesRoute(w http.ResponseWriter, r *http.Request) error {
	views := []serviceView{}
	for _, svc := range s.services() {
		views = append(views, serviceView{Service: svc.Service, URL: svc.URL, Up: svc.up, Info: svc.info})
	}
	return writeJSON(w, http.StatusOK, map[string]any{"services": views})
}
