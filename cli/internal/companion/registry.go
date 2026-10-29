package companion

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"time"

	"gopkg.in/yaml.v3"
)

// overseer is the part of an Overseer the companion reads to choose the local reviewer.
type overseer struct {
	Subject   string   `json:"subject"`
	Name      string   `json:"name"`
	Roles     []string `json:"roles"`
	Qualified bool     `json:"qualified"`
}

// registry is the OverseerRegistry the companion stamps reviewers from: Options.Registry, else
// the running services' registries merged, else Options.FallbackRegistry.
func (s *Server) registry(ctx context.Context) ([]byte, []overseer, error) {
	data, err := s.registryJSON(ctx)
	if err != nil {
		return nil, nil, err
	}
	var reg struct {
		Overseers []overseer `json:"overseers"`
	}
	if err := json.Unmarshal(data, &reg); err != nil {
		return nil, nil, errorf(http.StatusBadGateway, "the overseer registry is not an OverseerRegistry: %v", err)
	}
	return data, reg.Overseers, nil
}

func (s *Server) registryJSON(ctx context.Context) ([]byte, error) {
	if s.opts.Registry != "" {
		return readRegistryYAML(s.opts.Registry, time.Now())
	}
	var merged mergedRegistry
	found := false
	for _, svc := range s.running() {
		a, err := call(ctx, s.client, http.MethodGet, svc.base()+"/registry", nil)
		if err != nil || a.status != http.StatusOK {
			continue
		}
		var reg mergedRegistry
		if json.Unmarshal(a.body, &reg) != nil {
			continue
		}
		merged.add(reg)
		found = true
	}
	if found {
		return json.Marshal(merged)
	}
	if s.opts.FallbackRegistry != "" {
		return readRegistryYAML(s.opts.FallbackRegistry, time.Now())
	}
	return nil, errorf(http.StatusServiceUnavailable,
		"no overseer registry: no running service answers GET /oversight/registry and no --registry is given")
}

// yamlRegistry is an overseer registry in Verify's format (spec/verify/overseer-registry.schema.yaml).
type yamlRegistry struct {
	Overseers []struct {
		Subject   string   `yaml:"subject"`
		Name      string   `yaml:"name"`
		Roles     []string `yaml:"roles"`
		Groups    []string `yaml:"groups"`
		ReportsTo *string  `yaml:"reports_to"`
		Authority []struct {
			Controls []string `yaml:"controls"`
			Actions  []string `yaml:"actions"`
		} `yaml:"authority"`
		Attestations []struct {
			ID          string  `yaml:"id"`
			CompletedAt string  `yaml:"completed_at"`
			ExpiresAt   *string `yaml:"expires_at"`
		} `yaml:"attestations"`
		Qualified       bool    `yaml:"qualified"`
		QualifiedReason *string `yaml:"qualified_reason"`
	} `yaml:"overseers"`
}

type authorityJSON struct {
	Controls []string `json:"controls"`
	Actions  []string `json:"actions,omitempty"`
}

type attestationJSON struct {
	ID          string  `json:"id"`
	CompletedAt string  `json:"completedAt"`
	ExpiresAt   *string `json:"expiresAt"`
	Expired     bool    `json:"expired"`
}

type overseerJSON struct {
	Subject         string            `json:"subject"`
	Name            string            `json:"name"`
	Roles           []string          `json:"roles"`
	Groups          []string          `json:"groups"`
	ReportsTo       *string           `json:"reportsTo"`
	Authority       []authorityJSON   `json:"authority"`
	Attestations    []attestationJSON `json:"attestations"`
	Qualified       bool              `json:"qualified"`
	QualifiedReason *string           `json:"qualifiedReason"`
}

// mergedRegistry is the services' registries as one: each person once, with the roles, groups,
// authority and attestations every service gives them. Each service still checks its own registry
// when a write reaches it, so a role one service grants never acts in another.
type mergedRegistry struct {
	Overseers []overseerJSON    `json:"overseers"`
	Owners    []json.RawMessage `json:"owners"`
}

func (m *mergedRegistry) add(reg mergedRegistry) {
	if m.Overseers == nil {
		m.Overseers = []overseerJSON{}
	}
	if m.Owners == nil {
		m.Owners = []json.RawMessage{}
	}
	m.Owners = append(m.Owners, reg.Owners...)
	for _, o := range reg.Overseers {
		i := slices.IndexFunc(m.Overseers, func(e overseerJSON) bool { return e.Subject == o.Subject })
		if i < 0 {
			m.Overseers = append(m.Overseers, o)
			continue
		}
		e := &m.Overseers[i]
		e.Roles = union(e.Roles, o.Roles)
		e.Groups = union(e.Groups, o.Groups)
		e.Authority = append(e.Authority, o.Authority...)
		for _, att := range o.Attestations {
			if !slices.ContainsFunc(e.Attestations, func(x attestationJSON) bool { return x.ID == att.ID }) {
				e.Attestations = append(e.Attestations, att)
			}
		}
		if !e.Qualified && o.Qualified {
			e.Qualified, e.QualifiedReason = true, nil
		}
	}
}

func union(a, b []string) []string {
	out := slices.Clone(a)
	for _, s := range b {
		if !slices.Contains(out, s) {
			out = append(out, s)
		}
	}
	return out
}

// readRegistryYAML reads a Verify overseer registry and answers it as an OverseerRegistry, each
// attestation marked expired when its expiry is before now.
func readRegistryYAML(file string, now time.Time) ([]byte, error) {
	data, err := os.ReadFile(file)
	if err != nil {
		return nil, errorf(http.StatusServiceUnavailable, "cannot read the overseer registry: %v", err)
	}
	var reg yamlRegistry
	if err := yaml.Unmarshal(data, &reg); err != nil {
		return nil, errorf(http.StatusServiceUnavailable, "%s is not an overseer registry: %v", file, err)
	}
	overseers := []overseerJSON{}
	for _, o := range reg.Overseers {
		out := overseerJSON{Subject: o.Subject, Name: o.Name, Roles: nonNil(o.Roles), Groups: nonNil(o.Groups),
			ReportsTo: o.ReportsTo, Authority: []authorityJSON{}, Attestations: []attestationJSON{},
			Qualified: o.Qualified, QualifiedReason: o.QualifiedReason}
		for _, a := range o.Authority {
			out.Authority = append(out.Authority, authorityJSON{Controls: a.Controls, Actions: a.Actions})
		}
		for _, a := range o.Attestations {
			out.Attestations = append(out.Attestations, attestationJSON{ID: a.ID, CompletedAt: a.CompletedAt,
				ExpiresAt: a.ExpiresAt, Expired: a.ExpiresAt != nil && expired(*a.ExpiresAt, now)})
		}
		overseers = append(overseers, out)
	}
	return json.Marshal(map[string]any{"overseers": overseers, "owners": []any{}})
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// expired says whether a date or time is before now.
func expired(at string, now time.Time) bool {
	for _, layout := range []string{time.DateOnly, time.RFC3339Nano} {
		if t, err := time.Parse(layout, at); err == nil {
			return t.Before(now)
		}
	}
	return false
}

func (s *Server) registryRoute(w http.ResponseWriter, r *http.Request) error {
	data, err := s.registryJSON(r.Context())
	if err != nil {
		return err
	}
	writePassed(w, &passed{status: http.StatusOK, body: data})
	return nil
}

// localReviewer is who the console acts as: a person of the registry and the roles they act in.
type localReviewer struct {
	Subject string   `json:"subject"`
	Name    string   `json:"name"`
	Roles   []string `json:"roles"`
}

func (s *Server) reviewerPath() string { return filepath.Join(s.opts.Home, "reviewer.json") }

// reviewer is the reviewer chosen with PUT, or until one is, the first qualified overseer of the
// registry acting in all of their roles.
func (s *Server) reviewer(ctx context.Context) (localReviewer, error) {
	s.reviewerMu.Lock()
	data, err := os.ReadFile(s.reviewerPath())
	s.reviewerMu.Unlock()
	if err == nil {
		var me localReviewer
		if err := json.Unmarshal(data, &me); err != nil || me.Subject == "" {
			return localReviewer{}, errorf(http.StatusInternalServerError, "%s is not a reviewer: delete it or PUT a reviewer", s.reviewerPath())
		}
		return me, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return localReviewer{}, err
	}
	_, overseers, err := s.registry(ctx)
	if err != nil {
		return localReviewer{}, err
	}
	for _, o := range overseers {
		if o.Qualified {
			return localReviewer{Subject: o.Subject, Name: o.Name, Roles: nonNil(o.Roles)}, nil
		}
	}
	return localReviewer{}, errorf(http.StatusServiceUnavailable, "the overseer registry has no qualified overseer to act as")
}

func (s *Server) getReviewer(w http.ResponseWriter, r *http.Request) error {
	me, err := s.reviewer(r.Context())
	if err != nil {
		return err
	}
	return writeJSON(w, http.StatusOK, me)
}

// putReviewer keeps a person of the registry, acting in roles they hold, as the local reviewer.
// An unqualified person may be chosen; the services refuse their decisions with the reason.
func (s *Server) putReviewer(w http.ResponseWriter, r *http.Request) error {
	var me localReviewer
	if err := json.NewDecoder(r.Body).Decode(&me); err != nil {
		return errorf(http.StatusBadRequest, "the body must be {subject, roles}: %v", err)
	}
	_, overseers, err := s.registry(r.Context())
	if err != nil {
		return err
	}
	i := slices.IndexFunc(overseers, func(o overseer) bool { return o.Subject == me.Subject })
	if i < 0 {
		return errorf(http.StatusUnprocessableEntity, "'%s' is not an overseer in the registry", me.Subject)
	}
	o := overseers[i]
	if len(me.Roles) == 0 {
		return errorf(http.StatusUnprocessableEntity, "choose at least one of %s's roles", o.Name)
	}
	for _, role := range me.Roles {
		if !slices.Contains(o.Roles, role) {
			return errorf(http.StatusUnprocessableEntity, "%s does not hold the role '%s'", o.Name, role)
		}
	}
	me.Name = o.Name
	data, err := json.MarshalIndent(me, "", "  ")
	if err != nil {
		return err
	}
	s.reviewerMu.Lock()
	defer s.reviewerMu.Unlock()
	if err := os.MkdirAll(s.opts.Home, 0o755); err != nil {
		return fmt.Errorf("cannot keep the reviewer: %w", err)
	}
	if err := os.WriteFile(s.reviewerPath(), append(data, '\n'), 0o644); err != nil {
		return fmt.Errorf("cannot keep the reviewer: %w", err)
	}
	return writeJSON(w, http.StatusOK, me)
}
