package companion

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// event is the part of a HumanEvent the companion reads to list, order and route it.
type event struct {
	ID          string          `json:"id"`
	State       string          `json:"state"`
	Pending     bool            `json:"pending"`
	Pattern     string          `json:"pattern"`
	PatternName string          `json:"patternName"`
	Control     json.RawMessage `json:"control"`
	Flavor      string          `json:"flavor"`
	Mode        string          `json:"mode"`
	Blocking    bool            `json:"blocking"`
	Subject     struct {
		Kind      string  `json:"kind"`
		Summary   string  `json:"summary"`
		Service   *string `json:"service"`
		Agent     *string `json:"agent"`
		FlowID    *string `json:"flowId"`
		SessionID *string `json:"sessionId"`
		Ref       *string `json:"ref"`
	} `json:"subject"`
	Ask struct {
		Question string `json:"question"`
	} `json:"ask"`
	Routing struct {
		Queue    string `json:"queue"`
		Required int    `json:"required"`
	} `json:"routing"`
	Holder json.RawMessage `json:"holder"`
	SLA    struct {
		State  string  `json:"state"`
		DueAt  *string `json:"dueAt"`
		Target *string `json:"target"`
	} `json:"sla"`
	AIRecommendation json.RawMessage `json:"aiRecommendation"`
	Shadow           bool            `json:"shadow"`
	DetectedAt       string          `json:"detectedAt"`
	Trigger          struct {
		DetectedAt string `json:"detectedAt"`
	} `json:"trigger"`
	ClosedAt  *string         `json:"closedAt"`
	Outcome   json.RawMessage `json:"outcome"`
	Decisions []struct {
		Reviewer struct {
			Subject string `json:"subject"`
		} `json:"reviewer"`
		Action string `json:"action"`
	} `json:"decisions"`
	Conclusion *string `json:"conclusion"`
	Simulated  bool    `json:"simulated"`
	Now        *string `json:"now"`
}

// row is a HumanEventRow: the event as a queue lists it, with the service that owns it.
type row struct {
	ID          string          `json:"id"`
	State       string          `json:"state"`
	Pending     bool            `json:"pending"`
	Pattern     string          `json:"pattern"`
	PatternName string          `json:"patternName"`
	Control     json.RawMessage `json:"control"`
	Flavor      string          `json:"flavor"`
	Mode        string          `json:"mode"`
	Blocking    bool            `json:"blocking"`
	Subject     struct {
		Kind    string  `json:"kind"`
		Summary string  `json:"summary"`
		Service *string `json:"service"`
		Agent   *string `json:"agent"`
		Ref     *string `json:"ref"`
	} `json:"subject"`
	Ask struct {
		Question string `json:"question"`
	} `json:"ask"`
	Queue     string `json:"queue"`
	Reviewers struct {
		Required int `json:"required"`
		Voted    int `json:"voted"`
	} `json:"reviewers"`
	Holder json.RawMessage `json:"holder"`
	SLA    struct {
		State  string  `json:"state"`
		DueAt  *string `json:"dueAt"`
		Target *string `json:"target"`
	} `json:"sla"`
	AIRecommendation json.RawMessage `json:"aiRecommendation"`
	Shadow           bool            `json:"shadow"`
	DetectedAt       string          `json:"detectedAt"`
	ClosedAt         *string         `json:"closedAt"`
	Outcome          json.RawMessage `json:"outcome"`
	DecidedBy        []string        `json:"decidedBy"`
	Conclusion       *string         `json:"conclusion"`
	Simulated        bool            `json:"simulated"`
	Now              *string         `json:"now,omitempty"`
	Owner            struct {
		Service string `json:"service"`
	} `json:"owner"`
}

func (e *event) row(owner string) row {
	var r row
	r.ID, r.State, r.Pending, r.Pattern, r.PatternName = e.ID, e.State, e.Pending, e.Pattern, e.PatternName
	r.Control, r.Flavor, r.Mode, r.Blocking = e.Control, e.Flavor, e.Mode, e.Blocking
	r.Subject.Kind, r.Subject.Summary = e.Subject.Kind, e.Subject.Summary
	r.Subject.Service, r.Subject.Agent, r.Subject.Ref = e.Subject.Service, e.Subject.Agent, e.Subject.Ref
	r.Ask.Question = e.Ask.Question
	r.Queue = e.Routing.Queue
	r.Reviewers.Required, r.Reviewers.Voted = e.required(), e.voted()
	r.Holder = e.Holder
	r.SLA.State, r.SLA.DueAt, r.SLA.Target = e.SLA.State, e.SLA.DueAt, e.SLA.Target
	r.AIRecommendation, r.Shadow, r.DetectedAt = e.AIRecommendation, e.Shadow, e.detectedAt()
	r.ClosedAt, r.Outcome, r.DecidedBy = e.ClosedAt, e.Outcome, e.decidedBy()
	r.Conclusion, r.Simulated, r.Now = e.Conclusion, e.Simulated, e.Now
	r.Owner.Service = owner
	return r
}

func (e *event) detectedAt() string {
	if e.DetectedAt != "" {
		return e.DetectedAt
	}
	return e.Trigger.DetectedAt
}

// required is how many agreeing votes settle the event.
func (e *event) required() int { return max(1, e.Routing.Required) }

// settles says whether a decision is a vote, as opposed to a move that leaves the event pending.
func settles(action string) bool {
	switch action {
	case "ESCALATE", "SEND_BACK", "REQUEST_MORE_INFO", "RECUSE":
		return false
	}
	return true
}

// decidedBy are the subjects whose votes settled a closed event, in order: the last votes, as many
// as it required. None while it is pending, or when it closed without a person's decision.
func (e *event) decidedBy() []string {
	out := []string{}
	var outcome struct {
		Action     *string `json:"action"`
		SlaOutcome string  `json:"slaOutcome"`
	}
	if e.Pending || json.Unmarshal(e.Outcome, &outcome) != nil || outcome.Action == nil {
		return out
	}
	switch outcome.SlaOutcome {
	case "TIMED_OUT", "ABANDONED", "KILLED":
		return out
	}
	for i := len(e.Decisions) - 1; i >= 0 && len(out) < e.required(); i-- {
		if d := e.Decisions[i]; settles(d.Action) {
			out = append(out, d.Reviewer.Subject)
		}
	}
	slices.Reverse(out)
	return out
}

// voted is how many have voted in the current round: those awaiting a second vote's, the
// deciders of a closed one, none otherwise.
func (e *event) voted() int {
	if !e.Pending {
		return len(e.decidedBy())
	}
	if e.State != "AWAITING_SECOND" {
		return 0
	}
	// The trailing votes that agree with the last; a disagreement starts a new round.
	n, action := 0, ""
	for i := len(e.Decisions) - 1; i >= 0 && n < e.required()-1; i-- {
		d := e.Decisions[i]
		if !settles(d.Action) || (action != "" && d.Action != action) {
			break
		}
		action = d.Action
		n++
	}
	return n
}

// due is when the SLA falls due while its clock runs; "" when it is paused or stopped.
func (e *event) due() string {
	switch e.SLA.State {
	case "running", "at-risk", "overdue":
		if e.SLA.DueAt != nil {
			return *e.SLA.DueAt
		}
	}
	return ""
}

func (e *event) closedAt() string {
	if e.ClosedAt != nil {
		return *e.ClosedAt
	}
	return ""
}

// before orders events as every service orders its own: pending before resolved; pending most
// urgent first (overdue, then least time left, then oldest); resolved by closedAt, newest first.
func before(a, b *event) bool {
	if a.Pending != b.Pending {
		return a.Pending
	}
	if !a.Pending {
		if ca, cb := a.closedAt(), b.closedAt(); ca != cb {
			return later(ca, cb)
		}
		return false
	}
	if oa, ob := a.SLA.State == "overdue", b.SLA.State == "overdue"; oa != ob {
		return oa
	}
	if da, db := a.due(), b.due(); da != db {
		switch {
		case da == "":
			return false
		case db == "":
			return true
		}
		return later(db, da)
	}
	if ta, tb := a.detectedAt(), b.detectedAt(); ta != tb {
		return later(tb, ta)
	}
	return false
}

// later says whether RFC 3339 time a is after b; a time that does not parse comes before every one
// that does.
func later(a, b string) bool {
	ta, errA := time.Parse(time.RFC3339Nano, a)
	tb, errB := time.Parse(time.RFC3339Nano, b)
	if errA != nil || errB != nil {
		return errA == nil && errB != nil
	}
	return ta.After(tb)
}

// companionParams are the list parameters the companion acts on itself; every other parameter is
// passed to the services as it is.
var companionParams = []string{"routable", "service", "cursor", "limit", "offset"}

// encodeCursor writes where the next page starts in each service's own list.
func encodeCursor(offsets map[string]int) string {
	data, _ := json.Marshal(offsets)
	return base64.RawURLEncoding.EncodeToString(data)
}

func decodeCursor(text string) (map[string]int, bool) {
	data, err := base64.RawURLEncoding.DecodeString(text)
	offsets := map[string]int{}
	if err != nil || json.Unmarshal(data, &offsets) != nil {
		return nil, false
	}
	for _, n := range offsets {
		if n < 0 {
			return nil, false
		}
	}
	return offsets, true
}

// listed is one service's page of events from its cursor offset.
type listed struct {
	svc    string
	offset int
	total  int
	events []event
	taken  int
}

// events answers the merged list. Each page asks every service for limit events from that
// service's offset in the cursor, merges them in the services' shared order, keeps the first
// limit, and hands out a cursor past what it took from each.
func (s *Server) events(w http.ResponseWriter, r *http.Request) error {
	query := r.URL.Query()
	limit := 50
	if v := query.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 200 {
			return errorf(http.StatusBadRequest, "limit must be a whole number from 1 to 200, not '%s'", v)
		}
		limit = n
	}
	offsets := map[string]int{}
	if v := query.Get("cursor"); v != "" {
		c, ok := decodeCursor(v)
		if !ok {
			return errorf(http.StatusBadRequest, "cursor is not one the companion handed out; pass the next of the previous page")
		}
		offsets = c
	}
	forward := url.Values{}
	for k, v := range query {
		if !slices.Contains(companionParams, k) {
			forward[k] = v
		}
	}
	if v := query.Get("routable"); v != "" {
		if v != "me" {
			return errorf(http.StatusBadRequest, "routable must be me, not '%s'", v)
		}
		me, err := s.reviewer(r.Context())
		if err != nil {
			return err
		}
		forward.Set("roles", strings.Join(me.Roles, ","))
		forward.Set("reviewer", me.Subject)
	}
	svcs := s.running()
	if name := query.Get("service"); name != "" {
		svcs = slices.DeleteFunc(svcs, func(svc service) bool { return svc.Service != name })
	}
	replies, err := s.gather(r.Context(), svcs, func(svc service) string {
		q := url.Values{}
		for k, v := range forward {
			q[k] = v
		}
		q.Set("offset", strconv.Itoa(offsets[svc.Service]))
		q.Set("limit", strconv.Itoa(limit))
		return "/events?" + q.Encode()
	})
	if err != nil {
		return err
	}
	var lists []*listed
	total := 0
	for _, rep := range replies {
		var page struct {
			Events []event `json:"events"`
			Total  int     `json:"total"`
		}
		if err := json.Unmarshal(rep.body, &page); err != nil {
			return errorf(http.StatusBadGateway, "the events of %s are not an EventsPage: %v", rep.svc.Service, err)
		}
		lists = append(lists, &listed{svc: rep.svc.Service, offset: offsets[rep.svc.Service], total: page.Total, events: page.Events})
		total += page.Total
	}
	rows := []row{}
	for len(rows) < limit {
		var next *listed
		for _, l := range lists {
			if l.taken < len(l.events) && (next == nil || before(&l.events[l.taken], &next.events[next.taken])) {
				next = l
			}
		}
		if next == nil {
			break
		}
		rows = append(rows, next.events[next.taken].row(next.svc))
		next.taken++
	}
	var cursor *string
	nextOffsets := map[string]int{}
	more := false
	for _, l := range lists {
		nextOffsets[l.svc] = l.offset + l.taken
		more = more || l.offset+l.taken < l.total
	}
	if more && len(rows) > 0 {
		c := encodeCursor(nextOffsets)
		cursor = &c
	}
	return writeJSON(w, http.StatusOK, map[string]any{"rows": rows, "total": total, "next": cursor})
}

// owner is the running service that answers for the event, the first by name should two, with its
// answer.
func (s *Server) owner(ctx context.Context, id string) (service, answer, error) {
	svcs := s.running()
	answers := make([]answer, len(svcs))
	var wg sync.WaitGroup
	for i, svc := range svcs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			answers[i], _ = call(ctx, s.client, http.MethodGet, svc.base()+"/events/"+url.PathEscape(id), nil)
		}()
	}
	wg.Wait()
	for i, a := range answers {
		if a.status == http.StatusOK {
			return svcs[i], a, nil
		}
	}
	return service{}, answer{}, errorf(http.StatusNotFound, "no running service owns the event '%s'", id)
}

func (s *Server) event(w http.ResponseWriter, r *http.Request) error {
	_, a, err := s.owner(r.Context(), r.PathValue("id"))
	if err != nil {
		return err
	}
	writePassed(w, a.passed())
	return nil
}

// eventWrites are the writes to one event, each forwarded to its owner's
// /oversight/events/{id}/{write}.
var eventWrites = []string{"claim", "release", "decision", "escalate", "send-back", "resubmit", "withdraw", "extend", "reopen"}

func (s *Server) eventWrite(w http.ResponseWriter, r *http.Request) error {
	id, write := r.PathValue("id"), r.PathValue("write")
	if !slices.Contains(eventWrites, write) {
		return errorf(http.StatusNotFound, "no event write '%s'; one of %s", write, strings.Join(eventWrites, ", "))
	}
	body, err := readObject(r)
	if err != nil {
		return err
	}
	if err := s.stamp(r.Context(), body); err != nil {
		return err
	}
	owner, _, err := s.owner(r.Context(), id)
	if err != nil {
		return err
	}
	return s.forward(w, r.Context(), owner, "/events/"+url.PathEscape(id)+"/"+write, body)
}
