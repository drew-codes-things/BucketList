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

	store, err := NewStore(dataDir)
	if err != nil {
		log.Fatalf("open store: %v", err)
	}
	auth, err := NewAuth(dataDir, time.Now)
	if err != nil {
		log.Fatalf("load sessions: %v", err)
	}
	server := &http.Server{
		Addr:              address,
		Handler:           NewAPI(store, auth, setupToken),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	log.Printf("bucketlist listening on %s, data in %s", address, dataDir)
	log.Fatal(server.ListenAndServe())
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
