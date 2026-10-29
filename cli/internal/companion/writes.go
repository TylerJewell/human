package companion

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
)

// stamped is the reviewer on every write: the local reviewer, deciding in rehearsal, so the
// decision takes effect but its verdict never counts as evidence.
type stamped struct {
	Subject          string   `json:"subject"`
	Name             string   `json:"name"`
	Roles            []string `json:"roles"`
	IdentityProvider string   `json:"identityProvider"`
	Rehearsal        bool     `json:"rehearsal"`
}

// stamp sets the local reviewer on a write's body, replacing any reviewer the body names.
func (s *Server) stamp(ctx context.Context, body map[string]json.RawMessage) error {
	me, err := s.reviewer(ctx)
	if err != nil {
		return err
	}
	data, err := json.Marshal(stamped{Subject: me.Subject, Name: me.Name, Roles: me.Roles,
		IdentityProvider: "human-companion", Rehearsal: true})
	if err != nil {
		return err
	}
	body["reviewer"] = data
	return nil
}

// controlWrite forwards a flag, halt, contest or override to the service that declares its
// control. A flag that names no control goes to the only running service, or else to the service
// with an event on its flow or session.
func (s *Server) controlWrite(path string) func(http.ResponseWriter, *http.Request) error {
	return func(w http.ResponseWriter, r *http.Request) error {
		body, err := readObject(r)
		if err != nil {
			return err
		}
		if err := s.stamp(r.Context(), body); err != nil {
			return err
		}
		var to service
		control := text(body, "control")
		switch {
		case control != "":
			to, err = s.declaring(r.Context(), control)
		case path != "/flags":
			err = errorf(http.StatusUnprocessableEntity, "the body must name the control")
		default:
			to, err = s.flowOwner(r.Context(), text(body, "flowId"), text(body, "sessionId"))
		}
		if err != nil {
			return err
		}
		return s.forward(w, r.Context(), to, path, body)
	}
}

// declaring is the running service that declares the control.
func (s *Server) declaring(ctx context.Context, control string) (service, error) {
	replies, err := s.gather(ctx, s.running(), func(service) string { return "/controls" })
	if err != nil {
		return service{}, err
	}
	for _, rep := range replies {
		var page struct {
			Controls []struct {
				Control struct {
					RowID string `json:"rowId"`
				} `json:"control"`
			} `json:"controls"`
		}
		json.Unmarshal(rep.body, &page)
		for _, c := range page.Controls {
			if c.Control.RowID == control {
				return rep.svc, nil
			}
		}
	}
	return service{}, errorf(http.StatusNotFound, "no running service declares the control '%s'", control)
}

// flowOwner is the service a flag without a control goes to: the only running service, or the one
// with an event on the flow or session.
func (s *Server) flowOwner(ctx context.Context, flowID, sessionID string) (service, error) {
	running := s.running()
	if len(running) == 1 {
		return running[0], nil
	}
	if flowID != "" || sessionID != "" {
		for _, svc := range running {
			if s.hasFlow(ctx, svc, flowID, sessionID) {
				return svc, nil
			}
		}
	}
	return service{}, errorf(http.StatusUnprocessableEntity,
		"%d services are running and none has an event on this flow or session; name the control", len(running))
}

// hasFlow says whether any of the service's events, in any state, is on the flow or session.
func (s *Server) hasFlow(ctx context.Context, svc service, flowID, sessionID string) bool {
	const limit = 200
	for offset := 0; ; offset += limit {
		q := url.Values{"shadow": {"true"}, "offset": {strconv.Itoa(offset)}, "limit": {strconv.Itoa(limit)}}
		a, err := call(ctx, s.client, http.MethodGet, svc.base()+"/events?"+q.Encode(), nil)
		var page struct {
			Events []event `json:"events"`
			Total  int     `json:"total"`
		}
		if err != nil || a.status != http.StatusOK || json.Unmarshal(a.body, &page) != nil {
			return false
		}
		for _, e := range page.Events {
			if (flowID != "" && e.Subject.FlowID != nil && *e.Subject.FlowID == flowID) ||
				(sessionID != "" && e.Subject.SessionID != nil && *e.Subject.SessionID == sessionID) {
				return true
			}
		}
		if len(page.Events) == 0 || offset+len(page.Events) >= page.Total {
			return false
		}
	}
}

// revoke forwards a revocation to the service that lists the standing decision.
func (s *Server) revoke(w http.ResponseWriter, r *http.Request) error {
	id := r.PathValue("id")
	body, err := readObject(r)
	if err != nil {
		return err
	}
	if err := s.stamp(r.Context(), body); err != nil {
		return err
	}
	replies, err := s.gather(r.Context(), s.running(), func(service) string { return "/standing" })
	if err != nil {
		return err
	}
	for _, rep := range replies {
		var page struct {
			Standing []struct {
				ID string `json:"id"`
			} `json:"standing"`
		}
		json.Unmarshal(rep.body, &page)
		for _, d := range page.Standing {
			if d.ID == id {
				return s.forward(w, r.Context(), rep.svc, "/standing/"+url.PathEscape(id)+"/revoke", body)
			}
		}
	}
	return errorf(http.StatusNotFound, "no running service lists the standing decision '%s'", id)
}
