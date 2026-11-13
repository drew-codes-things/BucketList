package main

import (
	"log"
	"net/http"
	"os"
	"time"
)

func main() {
	address := envOr("BUCKET_ADDR", "127.0.0.1:8080")
	dataDir := envOr("BUCKET_DATA", "./data")
	setupToken := os.Getenv("BUCKET_SETUP_TOKEN")
	clientIPHeader := os.Getenv("BUCKET_CLIENT_IP_HEADER")

	store, err := NewStore(dataDir)
	if err != nil {
		log.Fatalf("open store: %v", err)
	}
	auth, err := NewAuth(dataDir, time.Now)
	if err != nil {
		log.Fatalf("load sessions: %v", err)
	}
	server := newHTTPServer(address, NewAPI(store, auth, setupToken, clientIPHeader))
	log.Printf("bucketlist listening on %s, data in %s", address, dataDir)
	log.Fatal(server.ListenAndServe())
}

const (
	requestReadTimeout   = 30 * time.Second
	responseWriteTimeout = 60 * time.Second
)

func newHTTPServer(address string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              address,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       requestReadTimeout,
		WriteTimeout:      responseWriteTimeout,
		IdleTimeout:       2 * time.Minute,
	}
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
