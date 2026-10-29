package companion

import (
	"encoding/json"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
)

// add merges two JSON values decoded into any: numbers are summed; arrays of numbers are added
// element by element, aligned at their ends (the newest day is last); other arrays are
// concatenated; objects are merged key by key; anything else keeps the first value.
func add(a, b any) any {
	switch x := a.(type) {
	case nil:
		return b
	case float64:
		if y, ok := b.(float64); ok {
			return x + y
		}
	case map[string]any:
		if y, ok := b.(map[string]any); ok {
			out := map[string]any{}
			for k, v := range x {
				out[k] = v
			}
			for k, v := range y {
				out[k] = add(out[k], v)
			}
			return out
		}
	case []any:
		if y, ok := b.([]any); ok {
			if numbers(x) && numbers(y) {
				long, short := x, y
				if len(y) > len(x) {
					long, short = y, x
				}
				out := append([]any{}, long...)
				for i := range short {
					j := len(long) - len(short) + i
					out[j] = out[j].(float64) + short[i].(float64)
				}
				return out
			}
			return append(append([]any{}, x...), y...)
		}
	}
	return a
}

// numbers says whether every element is a number; an empty array is either kind.
func numbers(values []any) bool {
	for _, v := range values {
		if _, ok := v.(float64); !ok {
			return false
		}
	}
	return true
}

// tag sets service on each object of a list that does not name one.
func tag(list any, svc string) {
	items, _ := list.([]any)
	for _, item := range items {
		if m, ok := item.(map[string]any); ok {
			if _, named := m["service"]; !named {
				m["service"] = svc
			}
		}
	}
}

// gatherObjects reads path from every running service, each answer decoded as an object.
func (s *Server) gatherObjects(r *http.Request, path string) ([]map[string]any, []string, error) {
	replies, err := s.gather(r.Context(), s.running(), func(service) string { return path })
	if err != nil {
		return nil, nil, err
	}
	var objects []map[string]any
	var names []string
	for _, rep := range replies {
		var m map[string]any
		if err := json.Unmarshal(rep.body, &m); err != nil || m == nil {
			return nil, nil, errorf(http.StatusBadGateway, "the answer of %s to %s is not a JSON object", rep.svc.Service, path)
		}
		objects, names = append(objects, m), append(names, rep.svc.Service)
	}
	return objects, names, nil
}

// withQuery is path with the request's parameters named in keys.
func withQuery(path string, r *http.Request, keys ...string) string {
	q := url.Values{}
	for _, k := range keys {
		if v := r.URL.Query().Get(k); v != "" {
			q.Set(k, v)
		}
	}
	if len(q) == 0 {
		return path
	}
	return path + "?" + q.Encode()
}

func zeros(n int) []any {
	out := make([]any, n)
	for i := range out {
		out[i] = 0.0
	}
	return out
}

// summary sums the services' summaries: every figure added, per-day arrays added day by day, halts
// concatenated.
func (s *Server) summary(w http.ResponseWriter, r *http.Request) error {
	objects, names, err := s.gatherObjects(r, withQuery("/summary", r, "control"))
	if err != nil {
		return err
	}
	loop := func() any { return map[string]any{"pending": 0.0, "overdue": 0.0, "raised7d": zeros(7)} }
	var merged any = map[string]any{
		"loop":   map[string]any{"in": loop(), "on": loop(), "over": loop()},
		"states": map[string]any{}, "patterns": map[string]any{}, "atRisk": 0.0, "overdue": 0.0,
		"halts": []any{}, "raised30d": zeros(30), "standing": map[string]any{"active": 0.0, "expiring": 0.0},
		"unownedControls": 0.0,
	}
	for i, m := range objects {
		tag(m["halts"], names[i])
		merged = add(merged, m)
	}
	return writeJSON(w, http.StatusOK, merged)
}

// quality merges the services' oversight quality: reviewers by subject with their rates
// recomputed from the decisions behind them, weeks by week, every count added, per-control lists
// concatenated and tagged with their service.
func (s *Server) quality(w http.ResponseWriter, r *http.Request) error {
	objects, names, err := s.gatherObjects(r, withQuery("/quality", r, "window"))
	if err != nil {
		return err
	}
	window := r.URL.Query().Get("window")
	if window == "" {
		window = "P30D"
	}
	var merged any = map[string]any{
		"window": window, "reviewTimes": []any{}, "outcomes": map[string]any{}, "heatmap": []any{},
		"coverage":  map[string]any{"triggers": 0.0, "reachedPerson": 0.0},
		"agreement": map[string]any{"twoPersonEvents": 0.0, "agreed": 0.0},
		"signals":   []any{},
	}
	var reviewers, weeks []map[string]any
	for i, m := range objects {
		for _, k := range []string{"reviewTimes", "heatmap", "signals"} {
			tag(m[k], names[i])
		}
		reviewers = append(reviewers, objectsOf(m["reviewers"])...)
		weeks = append(weeks, objectsOf(m["weekly"])...)
		delete(m, "reviewers")
		delete(m, "weekly")
		delete(m, "window")
		merged = add(merged, m)
	}
	out := merged.(map[string]any)
	out["reviewers"] = mergeReviewers(reviewers)
	out["weekly"] = mergeWeeks(weeks)
	return writeJSON(w, http.StatusOK, out)
}

func objectsOf(list any) []map[string]any {
	items, _ := list.([]any)
	var out []map[string]any
	for _, item := range items {
		if m, ok := item.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

// weighted is a mean of values, each weighted by the count behind it.
type weighted struct {
	sum, weight float64
	last        any
}

func (m *weighted) add(v any, weight float64) {
	if f, ok := v.(float64); ok {
		m.sum += f * weight
		m.weight += weight
		m.last = f
	}
}

// value is the weighted mean; with no weight behind it, the last value seen, or null.
func (m *weighted) value() any {
	if m.weight > 0 {
		return m.sum / m.weight
	}
	return m.last
}

// reviewerRates are averaged over a reviewer's decisions. medianReviewSeconds cannot be recomputed
// from the services' medians; its decision-weighted mean stands in for it.
var reviewerRates = []string{"approvalRate", "overrideRate", "agreementWithAi", "medianReviewSeconds"}

// mergeReviewers merges reviewer rows by subject: counts added, rates averaged over decisions, the
// oldest claim kept, and each reviewer's share of all decisions recomputed.
func mergeReviewers(rows []map[string]any) []any {
	type acc struct {
		row   map[string]any
		rates map[string]*weighted
	}
	bySubject := map[string]*acc{}
	var order []string
	total := 0.0
	for _, r := range rows {
		subject, _ := r["subject"].(string)
		a := bySubject[subject]
		if a == nil {
			a = &acc{row: map[string]any{}, rates: map[string]*weighted{}}
			for k, v := range r {
				a.row[k] = v
			}
			for _, k := range []string{"decisions", "open", "claimed"} {
				a.row[k] = 0.0
			}
			a.row["oldestClaimSeconds"] = nil
			for _, k := range reviewerRates {
				a.rates[k] = &weighted{}
			}
			bySubject[subject] = a
			order = append(order, subject)
		}
		n, _ := r["decisions"].(float64)
		total += n
		for _, k := range []string{"decisions", "open", "claimed"} {
			a.row[k] = add(a.row[k], r[k])
		}
		if a.row["name"] == nil {
			a.row["name"] = r["name"]
		}
		for _, k := range reviewerRates {
			a.rates[k].add(r[k], n)
		}
		if v, ok := r["oldestClaimSeconds"].(float64); ok {
			if cur, ok := a.row["oldestClaimSeconds"].(float64); !ok || v > cur {
				a.row["oldestClaimSeconds"] = v
			}
		}
	}
	out := []any{}
	for _, subject := range order {
		a := bySubject[subject]
		for _, k := range reviewerRates {
			a.row[k] = a.rates[k].value()
		}
		share := 0.0
		if total > 0 {
			share = a.row["decisions"].(float64) / total
		}
		a.row["share"] = share
		out = append(out, a.row)
	}
	return out
}

// mergeWeeks merges weekly rows by week: raised added, rates averaged over what was raised, other
// counts added.
func mergeWeeks(rows []map[string]any) []any {
	type acc struct {
		row   map[string]any
		rates map[string]*weighted
	}
	byWeek := map[string]*acc{}
	var order []string
	rate := func(k string) bool {
		return strings.HasSuffix(k, "Rate") || strings.HasSuffix(k, "Kept") || strings.HasSuffix(k, "Share")
	}
	for _, r := range rows {
		week, _ := r["week"].(string)
		raised, _ := r["raised"].(float64)
		a := byWeek[week]
		if a == nil {
			a = &acc{row: map[string]any{"week": week}, rates: map[string]*weighted{}}
			byWeek[week] = a
			order = append(order, week)
		}
		for k, v := range r {
			switch {
			case k == "week":
			case rate(k):
				if a.rates[k] == nil {
					a.rates[k] = &weighted{}
				}
				a.rates[k].add(v, raised)
			default:
				a.row[k] = add(a.row[k], v)
			}
		}
	}
	sort.Strings(order)
	out := []any{}
	for _, week := range order {
		a := byWeek[week]
		for k, m := range a.rates {
			a.row[k] = m.value()
		}
		out = append(out, a.row)
	}
	return out
}

// listed answers {key: [...]}: each service's list concatenated, every item tagged with its
// service.
func (s *Server) listed(path, key string) func(http.ResponseWriter, *http.Request) error {
	return func(w http.ResponseWriter, r *http.Request) error {
		objects, names, err := s.gatherObjects(r, path)
		if err != nil {
			return err
		}
		all := []any{}
		for i, m := range objects {
			tag(m[key], names[i])
			items, _ := m[key].([]any)
			all = append(all, items...)
		}
		return writeJSON(w, http.StatusOK, map[string]any{key: all})
	}
}

// verdicts merges the services' verdicts newest first, keeping at most limit when one is asked.
func (s *Server) verdicts(w http.ResponseWriter, r *http.Request) error {
	objects, names, err := s.gatherObjects(r, withQuery("/verdicts", r, "since", "limit"))
	if err != nil {
		return err
	}
	all := []any{}
	for i, m := range objects {
		tag(m["verdicts"], names[i])
		items, _ := m["verdicts"].([]any)
		all = append(all, items...)
	}
	at := func(v any) string {
		m, _ := v.(map[string]any)
		s, _ := m["at"].(string)
		return s
	}
	sort.SliceStable(all, func(i, j int) bool { return later(at(all[i]), at(all[j])) })
	if n, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && n >= 0 && n < len(all) {
		all = all[:n]
	}
	return writeJSON(w, http.StatusOK, map[string]any{"verdicts": all})
}
