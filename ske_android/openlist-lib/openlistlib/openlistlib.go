// Package openlistlib exposes the parts of OpenList that an Android app needs
// to run it inside its own process.
//
// It lives inside the OpenList module on purpose: bootstrap, db and flags are
// internal packages, so a wrapper from a separate module could not import them.
// `build.ps1` copies this directory into the OpenList source tree before
// running `gomobile bind`.
package openlistlib

import (
	"errors"
	"time"

	// Every driver registers itself from an init in its own package. The CLI
	// gets them through cmd/root.go; without this blank import only the few
	// pulled in by offline_download exist, and even Local is missing.
	_ "github.com/OpenListTeam/OpenList/v4/drivers"

	"github.com/OpenListTeam/OpenList/v4/cmd/flags"
	"github.com/OpenListTeam/OpenList/v4/internal/bootstrap"
	"github.com/OpenListTeam/OpenList/v4/internal/db"
	"github.com/OpenListTeam/OpenList/v4/pkg/utils"
	log "github.com/sirupsen/logrus"
)

// LogCallback receives every logrus entry instead of stdout/stderr, which an
// Android app process does not have.
type LogCallback interface {
	OnLog(level int16, time int64, message string)
}

// Event reports lifecycle transitions back to the host app.
type Event interface {
	OnStartError(t string, err string)
	OnShutdown(t string)
	OnProcessExit(code int)
}

var startFailedHookID, shutdownHookID string

// SetConfigData points OpenList at a writable directory. It must be called
// before Init; temp, bleve, log and the sqlite file are all derived from it.
func SetConfigData(path string) {
	flags.DataDir = path
}

func SetConfigDebug(b bool) {
	flags.Debug = b
}

func SetConfigLogStd(b bool) {
	flags.LogStd = b
}

func SetConfigNoPrefix(b bool) {
	flags.NoPrefix = b
}

// Init loads config, opens the database and wires the callbacks. It does not
// listen on the network yet; call Start for that.
func Init(event Event, cb LogCallback) error {
	// A second Init in the same process would otherwise stack hooks.
	if startFailedHookID != "" {
		bootstrap.RemoveEndpointStartFailedHook(startFailedHookID)
		startFailedHookID = ""
	}
	if shutdownHookID != "" {
		bootstrap.RemoveEndpointShutdownHook(shutdownHookID)
		shutdownHookID = ""
	}

	bootstrap.Init()
	startFailedHookID = bootstrap.RegisterEndpointStartFailedHook(event.OnStartError)
	shutdownHookID = bootstrap.RegisterEndpointShutdownHook(event.OnShutdown)

	if utils.Log == nil {
		return errors.New("utils.log is nil")
	}
	utils.Log.SetFormatter(&callbackFormatter{cb: cb})
	utils.Log.ExitFunc = event.OnProcessExit
	return nil
}

// Start serves HTTP until Shutdown. It blocks, so call it off the UI thread.
func Start() {
	bootstrap.Start()
}

func IsRunning(t string) bool {
	return bootstrap.IsRunning(t)
}

// Shutdown stops the server and checkpoints sqlite so the WAL does not stay
// behind when Android later kills the process.
func Shutdown(timeoutMs int64) error {
	bootstrap.Shutdown(time.Duration(timeoutMs) * time.Millisecond)
	ForceDBSync()
	return nil
}

func ForceDBSync() {
	gormDB := db.GetDb()
	if gormDB == nil {
		return
	}
	sqlDB, err := gormDB.DB()
	if err != nil {
		return
	}
	if _, err = sqlDB.Exec("PRAGMA wal_checkpoint(TRUNCATE)"); err != nil {
		log.Warnf("wal checkpoint failed: %v", err)
	}
	if _, err = sqlDB.Exec("PRAGMA synchronous=FULL"); err != nil {
		log.Warnf("set synchronous failed: %v", err)
	}
}

// callbackFormatter hands entries to the host app and discards the formatted
// bytes, so nothing is written to a file or console.
type callbackFormatter struct {
	cb LogCallback
}

func (f *callbackFormatter) Format(entry *log.Entry) ([]byte, error) {
	f.cb.OnLog(int16(entry.Level), entry.Time.UnixMilli(), entry.Message)
	return nil, nil
}
