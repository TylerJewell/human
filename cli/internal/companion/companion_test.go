package companion

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"sync"
	"testing"
)

const examples = "../../../spec/examples"

func example(t *testing.T, name string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(examples, name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func object(t *testing.T, data []byte) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func marshal(t *testing.T, v any) []byte {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// at is a time on the examples' day.
func at(hm string) string { return "2026-10-29T" + hm + ":00Z" }

// pending is a HumanEvent example made pending with the SLA state and due time given.
func pending(t *testing.T, file, id, sla, due string) []byte {
	e := object(t, example(t, file))
	e["id"] = id
	s := e["sla"].(map[string]any)
	s["state"], s["dueAt"] = sla, due
	return marshal(t, e)
}

// closed is the closed HumanEvent example with the id and closing time given.
func closed(t *testing.T, id, closedAt string) []byte {
	e := object(t, example(t, "HumanEvent.system-lifted"))
	e["id"], e["closedAt"] = id, closedAt
	return marshal(t, e)
}

type posted struct {
	path string
	body map[string]any
}

// fake is a service serving /oversight/* from spec/examples. Its events are listed in the order
// given, which is the order the contract asks a service to list them in.
type fake struct {
	name     string
	srv      *httptest.Server
	events   [][]byte
	controls []string
	standing []string
	conflict string
	quality  []byte

	mu      sync.Mutex
	queries []url.Values
	posts   []posted
}

func newFake(t *testing.T, name string, events [][]byte, controls, standing []string) *fake {
	f := &fake{name: name, events: events, controls: controls, standing: standing, quality: example(t, "Quality.basic")}
	mux := http.NewServeMux()
	reply := func(w http.ResponseWriter, status int, body []byte) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		w.Write(body)
	}
	find := func(id string) []byte {
		for _, e := range f.events {
			if object(t, e)["id"] == id {
				return e
			}
		}
		return nil
	}
	info := object(t, example(t, "ServiceInfo.patterns"))
	info["service"] = name
	mux.HandleFunc("GET /oversight/info", func(w http.ResponseWriter, r *http.Request) {
		reply(w, 200, marshal(t, info))
	})
	mux.HandleFunc("GET /oversight/events", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		f.mu.Lock()
		f.queries = append(f.queries, q)
		f.mu.Unlock()
		if q.Get("state") == "bogus" {
			reply(w, 400, []byte(`{"error":"state must be pending, resolved or a state name"}`))
			return
		}
		var kept []json.RawMessage
		for _, e := range f.events {
			p := object(t, e)["pending"].(bool)
			if (q.Get("state") == "pending" && !p) || (q.Get("state") == "resolved" && p) {
				continue
			}
			kept = append(kept, e)
		}
		var offset, limit int
		fmt.Sscan(q.Get("offset"), &offset)
		if _, err := fmt.Sscan(q.Get("limit"), &limit); err != nil {
			limit = 50
		}
		page := kept[min(offset, len(kept)):min(offset+limit, len(kept))]
		reply(w, 200, marshal(t, map[string]any{"events": append([]json.RawMessage{}, page...), "total": len(kept)}))
	})
	mux.HandleFunc("GET /oversight/events/{id}", func(w http.ResponseWriter, r *http.Request) {
		if e := find(r.PathValue("id")); e != nil {
			reply(w, 200, e)
			return
		}
		reply(w, 404, []byte(`{"error":"no such event"}`))
	})
	record := func(r *http.Request) {
		data, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		f.posts = append(f.posts, posted{path: r.URL.Path, body: object(t, data)})
		f.mu.Unlock()
	}
	mux.HandleFunc("POST /oversight/events/{id}/{write}", func(w http.ResponseWriter, r *http.Request) {
		e := find(r.PathValue("id"))
		if e == nil {
			reply(w, 404, []byte(`{"error":"no such event"}`))
			return
		}
		record(r)
		if r.PathValue("id") == f.conflict {
			reply(w, 409, example(t, "Error.conflict"))
			return
		}
		reply(w, 200, e)
	})
	for _, write := range []string{"flags", "halts", "contests", "overrides"} {
		mux.HandleFunc("POST /oversight/"+write, func(w http.ResponseWriter, r *http.Request) {
			record(r)
			reply(w, 200, f.events[0])
		})
	}
	mux.HandleFunc("POST /oversight/standing/{id}/revoke", func(w http.ResponseWriter, r *http.Request) {
		record(r)
		reply(w, 200, example(t, "StandingDecision.pattern"))
	})
	mux.HandleFunc("GET /oversight/summary", func(w http.ResponseWriter, r *http.Request) {
		reply(w, 200, example(t, "Summary.basic"))
	})
	mux.HandleFunc("GET /oversight/quality", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		q := f.quality
		f.mu.Unlock()
		reply(w, 200, q)
	})
	mux.HandleFunc("GET /oversight/verdicts", func(w http.ResponseWriter, r *http.Request) {
		v := object(t, example(t, "Verdict.upheld"))
		v["id"], v["at"] = "vd-"+name, map[string]string{"alpha": at("14:05"), "beta": at("14:10")}[name]
		reply(w, 200, marshal(t, map[string]any{"verdicts": []any{v}}))
	})
	mux.HandleFunc("GET /oversight/standing", func(w http.ResponseWriter, r *http.Request) {
		var list []any
		for _, id := range f.standing {
			d := object(t, example(t, "StandingDecision.pattern"))
			d["id"] = id
			list = append(list, d)
		}
		reply(w, 200, marshal(t, map[string]any{"standing": list}))
	})
	mux.HandleFunc("GET /oversight/halts", func(w http.ResponseWriter, r *http.Request) {
		h := object(t, example(t, "Halt.halted"))
		delete(h, "service")
		reply(w, 200, marshal(t, map[string]any{"halts": []any{h}}))
	})
	mux.HandleFunc("GET /oversight/controls", func(w http.ResponseWriter, r *http.Request) {
		var list []any
		for _, id := range f.controls {
			c := object(t, example(t, "ControlSettings.action"))
			c["control"].(map[string]any)["rowId"] = id
			list = append(list, c)
		}
		reply(w, 200, marshal(t, map[string]any{"controls": list}))
	})
	mux.HandleFunc("GET /oversight/registry", func(w http.ResponseWriter, r *http.Request) {
		reply(w, 200, example(t, "OverseerRegistry.basic"))
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fake) lastQuery() url.Values {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.queries[len(f.queries)-1]
}

func (f *fake) lastPost(t *testing.T) posted {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.posts) == 0 {
		t.Fatalf("%s received no write", f.name)
	}
	return f.posts[len(f.posts)-1]
}

func register(t *testing.T, home, name, url string) {
	t.Helper()
	dir := filepath.Join(home, "services")
	os.MkdirAll(dir, 0o755)
	reg := registration{Service: name, URL: url, Oversight: "/oversight", Contract: 2, StartedAt: at("14:00")}
	if err := os.WriteFile(filepath.Join(dir, name+".json"), marshal(t, reg), 0o644); err != nil {
		t.Fatal(err)
	}
}

type world struct {
	alpha, beta *fake
	home        string
	srv         *httptest.Server
}

// newWorld is a companion over two services. Merged in the contract's order, their events are
// a1 b1 a2 b2 a3 b3 b4 a4: the overdue ones by due time, then the running ones by time left, then
// the paused one, then the closed ones newest first.
func newWorld(t *testing.T, opts Options) *world {
	alphaEvents := [][]byte{
		pending(t, "HumanEvent.action", "a1", "overdue", at("14:10")),
		pending(t, "HumanEvent.change", "a2", "running", at("14:20")),
		pending(t, "HumanEvent.records", "a3", "at-risk", at("14:40")),
		closed(t, "a4", at("14:05")),
	}
	betaEvents := [][]byte{
		pending(t, "HumanEvent.output", "b1", "overdue", at("14:15")),
		pending(t, "HumanEvent.appeal", "b2", "running", at("14:30")),
		pending(t, "HumanEvent.system", "b3", "paused", at("14:00")),
		closed(t, "b4", at("14:50")),
	}
	flow := object(t, betaEvents[1])
	flow["subject"].(map[string]any)["flowId"] = "flow-b"
	betaEvents[1] = marshal(t, flow)

	w := &world{home: t.TempDir()}
	w.alpha = newFake(t, "alpha", alphaEvents, []string{"pt-0001"}, []string{"sd-a"})
	w.beta = newFake(t, "beta", betaEvents, []string{"lo-0012"}, []string{"sd-b"})
	w.beta.conflict = "b1"
	register(t, w.home, "alpha", w.alpha.srv.URL)
	register(t, w.home, "beta", w.beta.srv.URL)
	opts.Home = w.home
	w.srv = httptest.NewServer(New(opts).Handler())
	t.Cleanup(w.srv.Close)
	return w
}

func (w *world) do(t *testing.T, method, path string, body any) (int, []byte) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(marshal(t, body))
	}
	req, _ := http.NewRequest(method, w.srv.URL+path, reader)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(res.Body)
	return res.StatusCode, data
}

func (w *world) get(t *testing.T, path string, v any) {
	t.Helper()
	status, data := w.do(t, "GET", path, nil)
	if status != 200 {
		t.Fatalf("GET %s: %d %s", path, status, data)
	}
	if err := json.Unmarshal(data, v); err != nil {
		t.Fatal(err)
	}
}

type page struct {
	Rows  []row   `json:"rows"`
	Total int     `json:"total"`
	Next  *string `json:"next"`
}

func ids(rows []row) []string {
	var out []string
	for _, r := range rows {
		out = append(out, r.ID)
	}
	return out
}

func TestEventsMergedInOrder(t *testing.T) {
	w := newWorld(t, Options{})
	var p page
	w.get(t, "/api/companion/human/events", &p)
	if want := []string{"a1", "b1", "a2", "b2", "a3", "b3", "b4", "a4"}; !slices.Equal(ids(p.Rows), want) {
		t.Fatalf("order %v, want %v", ids(p.Rows), want)
	}
	if p.Total != 8 || p.Next != nil {
		t.Fatalf("total %d next %v", p.Total, p.Next)
	}
	b2 := p.Rows[3]
	if b2.Owner.Service != "beta" || b2.Queue != "system-owners" || b2.Pattern != "contest-appeal" ||
		b2.Reviewers.Required != 1 || b2.Reviewers.Voted != 0 || b2.Ask.Question == "" || b2.SLA.State != "running" {
		t.Fatalf("row %+v", b2)
	}
	a4 := p.Rows[7]
	if a4.Pending || *a4.ClosedAt != at("14:05") || a4.Reviewers.Required != 2 || a4.Reviewers.Voted != 2 ||
		len(a4.DecidedBy) != 2 || a4.Owner.Service != "alpha" {
		t.Fatalf("closed row %+v", a4)
	}

	var resolved page
	w.get(t, "/api/companion/human/events?state=resolved", &resolved)
	if !slices.Equal(ids(resolved.Rows), []string{"b4", "a4"}) || resolved.Total != 2 {
		t.Fatalf("resolved %v total %d", ids(resolved.Rows), resolved.Total)
	}
}

func TestRowsAreHumanEventRows(t *testing.T) {
	w := newWorld(t, Options{})
	_, data := w.do(t, "GET", "/api/companion/human/events?limit=1", nil)
	var got map[string]any
	json.Unmarshal(data, &got)
	want := object(t, example(t, "CompanionEventsPage.one"))["rows"].([]any)[0].(map[string]any)
	for key := range want {
		if _, ok := got["rows"].([]any)[0].(map[string]any)[key]; !ok {
			t.Errorf("row has no %s", key)
		}
	}
}

func TestEventsPagedWithOneCursor(t *testing.T) {
	w := newWorld(t, Options{})
	var all []string
	path := "/api/companion/human/events?limit=3"
	for pages := 0; ; pages++ {
		var p page
		w.get(t, path, &p)
		if p.Total != 8 || len(p.Rows) > 3 {
			t.Fatalf("page %d: total %d rows %d", pages, p.Total, len(p.Rows))
		}
		all = append(all, ids(p.Rows)...)
		if p.Next == nil {
			break
		}
		path = "/api/companion/human/events?limit=3&cursor=" + url.QueryEscape(*p.Next)
	}
	if want := []string{"a1", "b1", "a2", "b2", "a3", "b3", "b4", "a4"}; !slices.Equal(all, want) {
		t.Fatalf("paged %v, want %v", all, want)
	}
	// The third page starts past three of each service's events.
	if q := w.alpha.lastQuery(); q.Get("offset") != "3" || q.Get("limit") != "3" {
		t.Fatalf("alpha asked %v", q)
	}
	if status, _ := w.do(t, "GET", "/api/companion/human/events?cursor=nonsense!", nil); status != 400 {
		t.Fatalf("a bad cursor answered %d", status)
	}
}

func TestRoutableMeAndPassThrough(t *testing.T) {
	w := newWorld(t, Options{})
	var p page
	w.get(t, "/api/companion/human/events?routable=me&state=pending&pattern=action-approval&q=pay", &p)
	q := w.beta.lastQuery()
	want := url.Values{"roles": {"ai-system-owner"}, "reviewer": {"tyler.jewell@akka.io"}, "state": {"pending"},
		"pattern": {"action-approval"}, "q": {"pay"}, "offset": {"0"}, "limit": {"50"}}
	if !reflect.DeepEqual(q, want) {
		t.Fatalf("beta asked %v, want %v", q, want)
	}

	w.get(t, "/api/companion/human/events?service=alpha", &p)
	for _, r := range p.Rows {
		if r.Owner.Service != "alpha" {
			t.Fatalf("service=alpha listed %s of %s", r.ID, r.Owner.Service)
		}
	}
	if p.Total != 4 {
		t.Fatalf("service=alpha total %d", p.Total)
	}

	status, data := w.do(t, "GET", "/api/companion/human/events?state=bogus", nil)
	if status != 400 || !strings.Contains(string(data), "state must be") {
		t.Fatalf("a bad query answered %d %s", status, data)
	}
}

func TestEventFromItsOwner(t *testing.T) {
	w := newWorld(t, Options{})
	var e map[string]any
	w.get(t, "/api/companion/human/events/b3", &e)
	if e["id"] != "b3" || e["pattern"] != "halt-restart" {
		t.Fatalf("event %v", e["id"])
	}
	if status, _ := w.do(t, "GET", "/api/companion/human/events/nope", nil); status != 404 {
		t.Fatalf("an unknown event answered %d", status)
	}
}

var stampedTyler = map[string]any{"subject": "tyler.jewell@akka.io", "name": "Tyler Jewell",
	"roles": []any{"ai-system-owner"}, "identityProvider": "human-companion", "rehearsal": true}

func TestWritesForwardedStamped(t *testing.T) {
	w := newWorld(t, Options{})
	status, data := w.do(t, "POST", "/api/companion/human/events/b2/decision",
		map[string]any{"action": "APPROVE", "rationale": "ok", "reviewer": map[string]any{"subject": "mallory"}})
	if status != 200 || object(t, data)["id"] != "b2" {
		t.Fatalf("decision answered %d %s", status, data)
	}
	got := w.beta.lastPost(t)
	if got.path != "/oversight/events/b2/decision" || got.body["action"] != "APPROVE" || got.body["rationale"] != "ok" {
		t.Fatalf("beta received %+v", got)
	}
	if !reflect.DeepEqual(got.body["reviewer"], stampedTyler) {
		t.Fatalf("reviewer %v", got.body["reviewer"])
	}
	if len(w.alpha.posts) != 0 {
		t.Fatalf("alpha received %v", w.alpha.posts)
	}

	status, data = w.do(t, "POST", "/api/companion/human/events/b1/claim", nil)
	if status != 409 || !bytes.Equal(data, example(t, "Error.conflict")) {
		t.Fatalf("a conflict answered %d %s", status, data)
	}
	if status, _ := w.do(t, "POST", "/api/companion/human/events/nope/claim", nil); status != 404 {
		t.Fatalf("an unknown event answered %d", status)
	}
	if status, _ := w.do(t, "POST", "/api/companion/human/events/b2/explode", nil); status != 404 {
		t.Fatalf("an unknown write answered %d", status)
	}
}

func TestControlWritesGoToTheDeclaringService(t *testing.T) {
	w := newWorld(t, Options{})
	status, _ := w.do(t, "POST", "/api/companion/human/halts", map[string]any{"control": "lo-0012", "lift": false, "reason": "drift"})
	if got := w.beta.lastPost(t); status != 200 || got.path != "/oversight/halts" || got.body["reason"] != "drift" ||
		!reflect.DeepEqual(got.body["reviewer"], stampedTyler) {
		t.Fatalf("halt %d %+v", status, got)
	}
	w.do(t, "POST", "/api/companion/human/contests", map[string]any{"control": "pt-0001", "ref": "x", "grounds": "y"})
	if got := w.alpha.lastPost(t); got.path != "/oversight/contests" {
		t.Fatalf("contest went to %+v", got)
	}
	w.do(t, "POST", "/api/companion/human/overrides", map[string]any{"control": "lo-0012", "ref": "x", "action": "OVERRIDE", "rationale": "y"})
	if got := w.beta.lastPost(t); got.path != "/oversight/overrides" {
		t.Fatalf("override went to %+v", got)
	}
	w.do(t, "POST", "/api/companion/human/flags", map[string]any{"note": "wrong", "flowId": "flow-b"})
	if got := w.beta.lastPost(t); got.path != "/oversight/flags" || got.body["note"] != "wrong" {
		t.Fatalf("flag went to %+v", got)
	}
	if status, _ := w.do(t, "POST", "/api/companion/human/flags", map[string]any{"note": "?", "flowId": "flow-x"}); status != 422 {
		t.Fatalf("a flag on an unknown flow answered %d", status)
	}
	if status, _ := w.do(t, "POST", "/api/companion/human/halts", map[string]any{"control": "zz-9"}); status != 404 {
		t.Fatalf("an undeclared control answered %d", status)
	}
}

func TestStandingRevokeAndLists(t *testing.T) {
	w := newWorld(t, Options{})
	status, _ := w.do(t, "POST", "/api/companion/human/standing/sd-b/revoke", map[string]any{"reason": "done"})
	if got := w.beta.lastPost(t); status != 200 || got.path != "/oversight/standing/sd-b/revoke" ||
		!reflect.DeepEqual(got.body["reviewer"], stampedTyler) {
		t.Fatalf("revoke %d %+v", status, got)
	}
	if status, _ := w.do(t, "POST", "/api/companion/human/standing/sd-z/revoke", nil); status != 404 {
		t.Fatalf("an unknown standing decision answered %d", status)
	}

	for _, key := range []string{"standing", "halts", "controls"} {
		var list map[string][]map[string]any
		w.get(t, "/api/companion/human/"+key, &list)
		var services []any
		for _, item := range list[key] {
			services = append(services, item["service"])
		}
		if !reflect.DeepEqual(services, []any{"alpha", "beta"}) {
			t.Fatalf("%s tagged %v", key, services)
		}
	}

	var verdicts map[string][]map[string]any
	w.get(t, "/api/companion/human/verdicts?limit=1", &verdicts)
	if v := verdicts["verdicts"]; len(v) != 1 || v[0]["id"] != "vd-beta" || v[0]["service"] != "beta" {
		t.Fatalf("verdicts %v", v)
	}
}

func TestSummarySummed(t *testing.T) {
	w := newWorld(t, Options{})
	var s map[string]any
	w.get(t, "/api/companion/human/summary", &s)
	in := s["loop"].(map[string]any)["in"].(map[string]any)
	raised7d := in["raised7d"].([]any)
	raised30d := s["raised30d"].([]any)
	if in["pending"] != 2.0 || len(raised7d) != 7 || raised7d[6] != 2.0 || raised7d[0] != 0.0 ||
		len(raised30d) != 30 || raised30d[29] != 2.0 {
		t.Fatalf("loop %v raised30d %v", in, raised30d)
	}
	if s["states"].(map[string]any)["OPEN"] != 2.0 || s["patterns"].(map[string]any)["action-approval"] != 2.0 ||
		len(s["halts"].([]any)) != 2 || s["standing"].(map[string]any)["active"] != 0.0 || s["atRisk"] != 0.0 {
		t.Fatalf("summary %v", s)
	}
}

func TestQualityMerged(t *testing.T) {
	w := newWorld(t, Options{})
	q := object(t, w.beta.quality)
	q["reviewers"] = []any{
		map[string]any{"subject": "tyler.jewell@akka.io", "name": "Tyler Jewell", "decisions": 4, "approvalRate": 0.25,
			"overrideRate": 0.5, "agreementWithAi": nil, "medianReviewSeconds": 10, "open": 2, "claimed": 1,
			"oldestClaimSeconds": 300, "share": 0.5},
		map[string]any{"subject": "s.patel@example.com", "name": "S. Patel", "decisions": 4, "approvalRate": 1.0,
			"overrideRate": 0.0, "agreementWithAi": 0.5, "medianReviewSeconds": 30, "open": 0, "claimed": 0,
			"oldestClaimSeconds": nil, "share": 0.5},
	}
	q["weekly"] = []any{map[string]any{"week": "2026-W44", "raised": 3, "slaKept": 1.0}}
	a := object(t, w.alpha.quality)
	a["weekly"] = []any{map[string]any{"week": "2026-W44", "raised": 1, "slaKept": 0.0}}
	w.beta.mu.Lock()
	w.beta.quality = marshal(t, q)
	w.beta.mu.Unlock()
	w.alpha.mu.Lock()
	w.alpha.quality = marshal(t, a)
	w.alpha.mu.Unlock()

	var got map[string]any
	w.get(t, "/api/companion/human/quality?window=P30D", &got)
	reviewers := got["reviewers"].([]any)
	tyler, patel := reviewers[0].(map[string]any), reviewers[1].(map[string]any)
	if tyler["decisions"] != 16.0 || tyler["approvalRate"] != 0.625 || tyler["overrideRate"] != 0.125 ||
		tyler["share"] != 0.8 || tyler["open"] != 3.0 || tyler["oldestClaimSeconds"] != 300.0 ||
		tyler["agreementWithAi"] != nil || tyler["medianReviewSeconds"] != 34.0 {
		t.Fatalf("tyler %v", tyler)
	}
	if patel["share"] != 0.2 || patel["agreementWithAi"] != 0.5 {
		t.Fatalf("patel %v", patel)
	}
	if got["outcomes"].(map[string]any)["triggered"] != 26.0 || got["coverage"].(map[string]any)["triggers"] != 26.0 ||
		got["window"] != "P30D" {
		t.Fatalf("quality %v", got)
	}
	times := got["reviewTimes"].([]any)
	if len(times) != 2 || times[1].(map[string]any)["service"] != "beta" {
		t.Fatalf("reviewTimes %v", times)
	}
	weekly := got["weekly"].([]any)
	if week := weekly[0].(map[string]any); len(weekly) != 1 || week["raised"] != 4.0 || week["slaKept"] != 0.75 {
		t.Fatalf("weekly %v", weekly)
	}
}

func TestReviewerDefaultsAndPersists(t *testing.T) {
	w := newWorld(t, Options{})
	var me localReviewer
	w.get(t, "/api/companion/human/reviewer", &me)
	if me.Subject != "tyler.jewell@akka.io" || me.Name != "Tyler Jewell" || !slices.Equal(me.Roles, []string{"ai-system-owner"}) {
		t.Fatalf("default reviewer %+v", me)
	}
	if status, _ := w.do(t, "PUT", "/api/companion/human/reviewer", map[string]any{"subject": "tyler.jewell@akka.io", "roles": []string{"pilot"}}); status != 422 {
		t.Fatalf("a role not held answered %d", status)
	}
	if status, _ := w.do(t, "PUT", "/api/companion/human/reviewer", map[string]any{"subject": "who@example.com", "roles": []string{"x"}}); status != 422 {
		t.Fatalf("an unknown overseer answered %d", status)
	}
	status, _ := w.do(t, "PUT", "/api/companion/human/reviewer", map[string]any{"subject": "k.osei@example.com", "roles": []string{"risk-officer"}})
	if status != 200 {
		t.Fatalf("PUT answered %d", status)
	}
	if _, err := os.Stat(filepath.Join(w.home, "reviewer.json")); err != nil {
		t.Fatal(err)
	}
	w.get(t, "/api/companion/human/reviewer", &me)
	if me.Subject != "k.osei@example.com" || me.Name != "K. Osei" {
		t.Fatalf("kept reviewer %+v", me)
	}
	w.do(t, "POST", "/api/companion/human/events/a2/claim", nil)
	if r := w.alpha.lastPost(t).body["reviewer"].(map[string]any); r["subject"] != "k.osei@example.com" {
		t.Fatalf("stamped %v", r)
	}
}

func TestRegistryFromYAML(t *testing.T) {
	w := newWorld(t, Options{Registry: "../../../spec/fixtures/overseers.yaml"})
	var reg struct {
		Overseers []overseerJSON `json:"overseers"`
	}
	w.get(t, "/api/companion/human/registry", &reg)
	if len(reg.Overseers) != 8 {
		t.Fatalf("%d overseers", len(reg.Overseers))
	}
	osei := reg.Overseers[7]
	if osei.Subject != "k.osei@example.com" || osei.Qualified || *osei.QualifiedReason == "" ||
		!osei.Attestations[0].Expired || osei.Attestations[0].CompletedAt != "2025-01-10" {
		t.Fatalf("k.osei %+v", osei)
	}
	if okafor := reg.Overseers[5]; *okafor.ReportsTo != "r.chen@example.com" || len(okafor.Authority[0].Actions) != 6 {
		t.Fatalf("m.okafor %+v", okafor)
	}
	var me localReviewer
	w.get(t, "/api/companion/human/reviewer", &me)
	if me.Subject != "tyler.jewell@akka.io" || len(me.Roles) != 4 {
		t.Fatalf("default reviewer %+v", me)
	}
}

func TestServicesUpAndDown(t *testing.T) {
	w := newWorld(t, Options{})
	gone := httptest.NewServer(http.NotFoundHandler())
	gone.Close()
	register(t, w.home, "gamma", gone.URL)
	var got struct {
		Services []struct {
			Service string          `json:"service"`
			URL     string          `json:"url"`
			Up      bool            `json:"up"`
			Info    json.RawMessage `json:"info"`
		} `json:"services"`
	}
	w.get(t, "/api/companion/human/services", &got)
	if len(got.Services) != 3 {
		t.Fatalf("services %+v", got.Services)
	}
	alpha, gamma := got.Services[0], got.Services[2]
	if !alpha.Up || object(t, alpha.Info)["service"] != "alpha" || alpha.URL != w.alpha.srv.URL {
		t.Fatalf("alpha %+v", alpha)
	}
	if gamma.Service != "gamma" || gamma.Up || string(gamma.Info) != "null" {
		t.Fatalf("gamma %+v", gamma)
	}
	var p page
	w.get(t, "/api/companion/human/events", &p)
	if p.Total != 8 {
		t.Fatalf("a down service changed the total to %d", p.Total)
	}
}

func TestCORS(t *testing.T) {
	w := newWorld(t, Options{})
	for origin, allowed := range map[string]bool{"http://localhost:5182": true, "http://localhost:5181": true, "http://evil.example": false} {
		req, _ := http.NewRequest("OPTIONS", w.srv.URL+"/api/companion/human/reviewer", nil)
		req.Header.Set("Origin", origin)
		req.Header.Set("Access-Control-Request-Method", "PUT")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		if got := res.Header.Get("Access-Control-Allow-Origin"); (got == origin) != allowed {
			t.Fatalf("%s: allow origin %q", origin, got)
		}
	}
}

func TestConsoleWithFallback(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "index.html"), []byte("<!doctype html>console"), 0o644)
	os.MkdirAll(filepath.Join(dir, "assets"), 0o755)
	os.WriteFile(filepath.Join(dir, "assets", "app.js"), []byte("app()"), 0o644)
	w := newWorld(t, Options{ConsoleDir: dir})
	for path, want := range map[string]string{"/": "console", "/human/queue/he-1": "console", "/assets/app.js": "app()"} {
		status, data := w.do(t, "GET", path, nil)
		if status != 200 || !strings.Contains(string(data), want) {
			t.Fatalf("GET %s: %d %s", path, status, data)
		}
	}
	if status, data := w.do(t, "GET", "/api/companion/human/nothing", nil); status != 404 || !strings.Contains(string(data), "error") {
		t.Fatalf("an unknown route answered %d %s", status, data)
	}
}

func TestConformanceWithoutTheJar(t *testing.T) {
	w := newWorld(t, Options{ConformanceJar: filepath.Join(t.TempDir(), "missing.jar")})
	status, data := w.do(t, "POST", "/api/companion/human/conformance", map[string]any{"service": "alpha"})
	if status != 503 || !strings.Contains(string(data), "missing.jar") {
		t.Fatalf("conformance answered %d %s", status, data)
	}
}

func TestNoServices(t *testing.T) {
	srv := httptest.NewServer(New(Options{Home: t.TempDir()}).Handler())
	defer srv.Close()
	w := &world{srv: srv}
	var p page
	w.get(t, "/api/companion/human/events", &p)
	if len(p.Rows) != 0 || p.Total != 0 || p.Next != nil {
		t.Fatalf("page %+v", p)
	}
	var s map[string]any
	w.get(t, "/api/companion/human/summary", &s)
	if len(s["raised30d"].([]any)) != 30 {
		t.Fatalf("summary %v", s)
	}
	if status, _ := w.do(t, "GET", "/api/companion/human/reviewer", nil); status != 503 {
		t.Fatalf("a reviewer with no registry answered %d", status)
	}
}

func TestMergedRegistryJoinsPeopleAcrossServices(t *testing.T) {
	var m mergedRegistry
	m.add(mergedRegistry{Overseers: []overseerJSON{
		{Subject: "a@example.com", Roles: []string{"approver"}, Qualified: false},
		{Subject: "b@example.com", Roles: []string{"reviewer"}, Qualified: true},
	}})
	m.add(mergedRegistry{Overseers: []overseerJSON{
		{Subject: "a@example.com", Roles: []string{"approver", "admin"}, Qualified: true},
	}})
	if len(m.Overseers) != 2 {
		t.Fatalf("want 2 people, got %d", len(m.Overseers))
	}
	a := m.Overseers[0]
	if !slices.Equal(a.Roles, []string{"approver", "admin"}) || !a.Qualified {
		t.Fatalf("roles not joined or qualification not kept: %+v", a)
	}
	if m.Owners == nil {
		t.Fatal("owners must be an empty list, not null")
	}
}
