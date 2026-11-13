package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	sessionLifetime     = 12 * time.Hour
	freeAttempts        = 5
	overallFreeAttempts = 100
	baseLockout         = 30 * time.Second
	maxLockout          = 15 * time.Minute
	sourceMemory        = 24 * time.Hour
	maxTrackedSources   = 10000
	knownSourceMemory   = 30 * 24 * time.Hour
	maxKnownSources     = 1000
	sessionTokenBytes   = 32
)

var ErrLocked = errors.New("too many failed attempts")

func digest(value []byte) []byte {
	sum := sha256.Sum256(value)
	return sum[:]
}

type loginLimit struct {
	failures    int
	lockedUntil time.Time
	lastFailure time.Time
}

func (l *loginLimit) retryAfter(now time.Time) time.Duration {
	remaining := l.lockedUntil.Sub(now)
	if remaining < 0 {
		return 0
	}
	return remaining
}

func (l *loginLimit) recordFailure(now time.Time, allowed int) {
	l.failures++
	l.lastFailure = now
	if l.failures < allowed {
		return
	}
	lockout := baseLockout << (l.failures - allowed)
	if lockout > maxLockout || lockout <= 0 {
		lockout = maxLockout
	}
	l.lockedUntil = now.Add(lockout)
}

func (l *loginLimit) isIdle(now time.Time) bool {
	return l.retryAfter(now) == 0 && now.Sub(l.lastFailure) > sourceMemory
}

type Auth struct {
	path         string
	now          func() time.Time
	mu           sync.Mutex
	sessions     map[string]time.Time
	sources      map[string]*loginLimit
	knownSources map[string]time.Time
	overall      loginLimit
}

func NewAuth(root string, now func() time.Time) (*Auth, error) {
	auth := &Auth{
		path:         filepath.Join(root, "sessions.json"),
		now:          now,
		sessions:     map[string]time.Time{},
		sources:      map[string]*loginLimit{},
		knownSources: map[string]time.Time{},
	}
	data, err := os.ReadFile(auth.path)
	if errors.Is(err, os.ErrNotExist) {
		return auth, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &auth.sessions); err != nil {
		return nil, err
	}
	return auth, nil
}

func (a *Auth) persist() error {
	data, err := json.Marshal(a.sessions)
	if err != nil {
		return err
	}
	return writeFileAtomic(a.path, data)
}

func (a *Auth) RetryAfter(source string) time.Duration {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.retryAfterLocked(source)
}

func (a *Auth) retryAfterLocked(source string) time.Duration {
	now := a.now()
	var wait time.Duration
	if !a.isKnownSourceLocked(source, now) {
		wait = a.overall.retryAfter(now)
	}
	if limit, ok := a.sources[source]; ok {
		wait = max(wait, limit.retryAfter(now))
	}
	return wait
}

func (a *Auth) isKnownSourceLocked(source string, now time.Time) bool {
	lastSuccess, ok := a.knownSources[source]
	return ok && now.Sub(lastSuccess) <= knownSourceMemory
}

func (a *Auth) rememberKnownSourceLocked(source string, now time.Time) {
	if _, ok := a.knownSources[source]; !ok && len(a.knownSources) >= maxKnownSources {
		a.forgetOldestKnownSourceLocked()
	}
	a.knownSources[source] = now
}

func (a *Auth) forgetOldestKnownSourceLocked() {
	var oldest string
	var oldestSuccess time.Time
	for source, lastSuccess := range a.knownSources {
		if oldest == "" || lastSuccess.Before(oldestSuccess) {
			oldest, oldestSuccess = source, lastSuccess
		}
	}
	delete(a.knownSources, oldest)
}

func (a *Auth) recordFailureLocked(source string) {
	now := a.now()
	a.overall.recordFailure(now, overallFreeAttempts)
	limit, ok := a.sources[source]
	if !ok {
		a.forgetIdleSourcesLocked(now)
		if len(a.sources) >= maxTrackedSources {
			return
		}
		limit = &loginLimit{}
		a.sources[source] = limit
	}
	limit.recordFailure(now, freeAttempts)
}

func (a *Auth) forgetIdleSourcesLocked(now time.Time) {
	for source, limit := range a.sources {
		if limit.isIdle(now) {
			delete(a.sources, source)
		}
	}
}

func (a *Auth) recordSuccessLocked(source string) {
	now := a.now()
	delete(a.sources, source)
	a.rememberKnownSourceLocked(source, now)
	if a.overall.retryAfter(now) == 0 {
		a.overall = loginLimit{}
	}
}

func (a *Auth) Login(account *Account, authKey []byte, source string) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.retryAfterLocked(source) > 0 {
		return "", ErrLocked
	}
	if subtle.ConstantTimeCompare(digest(authKey), account.AuthDigest) != 1 {
		a.recordFailureLocked(source)
		return "", errors.New("invalid credentials")
	}
	a.recordSuccessLocked(source)
	raw := make([]byte, sessionTokenBytes)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	a.pruneLocked()
	a.sessions[sessionKey(token)] = a.now().Add(sessionLifetime)
	if err := a.persist(); err != nil {
		return "", err
	}
	return token, nil
}

func sessionKey(token string) string {
	return base64.RawURLEncoding.EncodeToString(digest([]byte(token)))
}

func (a *Auth) pruneLocked() {
	now := a.now()
	for key, expiry := range a.sessions {
		if now.After(expiry) {
			delete(a.sessions, key)
		}
	}
}

func (a *Auth) Valid(token string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	expiry, ok := a.sessions[sessionKey(token)]
	return ok && a.now().Before(expiry)
}

func (a *Auth) Logout(token string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.sessions, sessionKey(token))
	return a.persist()
}

func (a *Auth) RevokeAll() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.sessions = map[string]time.Time{}
	return a.persist()
}
