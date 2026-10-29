package companion

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"time"
)

// conformance runs the conformance suite against a registered service's dev routes and answers
// the suite's JSON report.
func (s *Server) conformance(w http.ResponseWriter, r *http.Request) error {
	body, err := readObject(r)
	if err != nil {
		return err
	}
	name := text(body, "service")
	if name == "" {
		return errorf(http.StatusBadRequest, "the body must name the service: {service}")
	}
	if _, err := os.Stat(s.opts.ConformanceJar); s.opts.ConformanceJar == "" || err != nil {
		return errorf(http.StatusServiceUnavailable,
			"the conformance suite is not built at '%s': run mvn -f java/human-conformance/pom.xml package, or pass --conformance-jar",
			s.opts.ConformanceJar)
	}
	var target *service
	for _, svc := range s.services() {
		if svc.Service == name {
			target = &svc
		}
	}
	if target == nil {
		return errorf(http.StatusNotFound, "no registered service '%s'", name)
	}
	report, err := os.CreateTemp("", "human-conformance-*.json")
	if err != nil {
		return err
	}
	report.Close()
	defer os.Remove(report.Name())
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Minute)
	defer cancel()
	out, runErr := exec.CommandContext(ctx, "java", "-jar", s.opts.ConformanceJar,
		"--url", target.URL, "--report", report.Name()).CombinedOutput()
	data, _ := os.ReadFile(report.Name())
	if len(data) == 0 || !json.Valid(data) {
		tail := strings.TrimSpace(string(out))
		if len(tail) > 2000 {
			tail = tail[len(tail)-2000:]
		}
		return errorf(http.StatusBadGateway, "the conformance suite wrote no report (%v): %s", runErr, tail)
	}
	writePassed(w, &passed{status: http.StatusOK, body: data})
	return nil
}
