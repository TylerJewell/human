// Command human-companion plays the akka CLI companion's part for human oversight: it discovers
// the local services that serve /oversight/*, merges their events into one API for consoles,
// stamps the local reviewer on every write, and forwards each write to the owning service.
package main

import (
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/akka/human/cli/internal/companion"
)

func main() {
	root := repoRoot()
	port := flag.Int("port", 9167, "the port to serve on, on the loopback addresses")
	home := flag.String("home", defaultHome(), "where services register (services/*.json) and the reviewer is kept")
	registry := flag.String("registry", "", "an overseer registry YAML; default: the first running service's, else spec/fixtures/overseers.yaml")
	consoleDir := flag.String("console-dir", existing(root, "console/apps/console/dist"), "the built console to serve at /")
	jar := flag.String("conformance-jar", inRepo(root, "java/human-conformance/target/human-conformance.jar"), "the conformance suite jar")
	flag.Parse()

	srv := companion.New(companion.Options{
		Home:             *home,
		Registry:         *registry,
		FallbackRegistry: existing(root, "spec/fixtures/overseers.yaml"),
		ConsoleDir:       *consoleDir,
		ConformanceJar:   *jar,
		Refresh:          2 * time.Second,
	})
	handler := srv.Handler()
	addr := strconv.Itoa(*port)
	served := 0
	for _, host := range []string{"127.0.0.1", "::1"} {
		ln, err := net.Listen("tcp", net.JoinHostPort(host, addr))
		if err != nil {
			log.Printf("not serving on %s: %v", host, err)
			continue
		}
		served++
		go func() { log.Fatal(http.Serve(ln, handler)) }()
	}
	if served == 0 {
		log.Fatalf("cannot listen on port %s", addr)
	}
	log.Printf("human-companion on http://localhost:%s (home %s, console %q)", addr, *home, *consoleDir)
	select {}
}

func defaultHome() string {
	if h := os.Getenv("AKKA_HUMAN_HOME"); h != "" {
		return h
	}
	userHome, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join(".akka", "human")
	}
	return filepath.Join(userHome, ".akka", "human")
}

// repoRoot is the akka/human checkout the binary runs from: the first directory, from the
// executable's then the working directory upward, that holds spec/oversight.schema.json.
func repoRoot() string {
	var starts []string
	if exe, err := os.Executable(); err == nil {
		starts = append(starts, filepath.Dir(exe))
	}
	if wd, err := os.Getwd(); err == nil {
		starts = append(starts, wd)
	}
	for _, dir := range starts {
		for {
			if _, err := os.Stat(filepath.Join(dir, "spec", "oversight.schema.json")); err == nil {
				return dir
			}
			parent := filepath.Dir(dir)
			if parent == dir {
				break
			}
			dir = parent
		}
	}
	return ""
}

// inRepo is rel within the checkout, or rel from cli/ when there is none.
func inRepo(root, rel string) string {
	if root == "" {
		return filepath.Join("..", filepath.FromSlash(rel))
	}
	return filepath.Join(root, filepath.FromSlash(rel))
}

// existing is inRepo when that path exists, else "".
func existing(root, rel string) string {
	p := inRepo(root, rel)
	if _, err := os.Stat(p); errors.Is(err, os.ErrNotExist) {
		return ""
	}
	return p
}
