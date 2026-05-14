package gobridge

import (
	"archive/zip"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	cryptozip "github.com/yeka/zip"
)

const minimalCertPEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n"
const minimalKeyPEM = "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\n"
const minimalConfigXML = `<configuration version="37"></configuration>`

func writeBackupZip(t *testing.T, dst string, entries map[string][]byte, fakeEncryptionBit bool) {
	t.Helper()
	f, err := os.Create(dst)
	if err != nil {
		t.Fatalf("create %s: %v", dst, err)
	}
	defer f.Close()
	w := zip.NewWriter(f)
	for name, data := range entries {
		hdr := &zip.FileHeader{Name: name, Method: zip.Deflate}
		if fakeEncryptionBit {
			hdr.Flags |= 0x1
		}
		hdr.SetMode(0o600)
		writer, err := w.CreateHeader(hdr)
		if err != nil {
			t.Fatalf("create header %s: %v", name, err)
		}
		if _, err := writer.Write(data); err != nil {
			t.Fatalf("write %s: %v", name, err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatalf("close zip: %v", err)
	}
}

func writeEncryptedZip(t *testing.T, dst string, entries map[string][]byte, password string) {
	t.Helper()
	f, err := os.Create(dst)
	if err != nil {
		t.Fatalf("create %s: %v", dst, err)
	}
	defer f.Close()
	w := cryptozip.NewWriter(f)
	for name, data := range entries {
		writer, err := w.Encrypt(name, password, cryptozip.AES256Encryption)
		if err != nil {
			t.Fatalf("encrypt header %s: %v", name, err)
		}
		if _, err := writer.Write(data); err != nil {
			t.Fatalf("write %s: %v", name, err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatalf("close encrypted zip: %v", err)
	}
}

func TestImportConfig_RoundTrip(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "src")
	dst := filepath.Join(dir, "dst")
	if err := os.MkdirAll(src, 0o700); err != nil {
		t.Fatal(err)
	}
	must := func(name, data string) {
		if err := os.WriteFile(filepath.Join(src, name), []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	must(configFileName, minimalConfigXML)
	must(certFileName, minimalCertPEM)
	must(keyFileName, minimalKeyPEM)

	prefsPath := filepath.Join(dir, prefsFileName)
	if err := os.WriteFile(prefsPath, []byte(`{"wifi_only_sync":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	asyncPath := filepath.Join(dir, asyncFileName)
	if err := os.WriteFile(asyncPath, []byte(`{"@syncup/vaults":"[]"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	extras, _ := json.Marshal([]map[string]string{
		{"name": prefsFileName, "path": prefsPath},
		{"name": asyncFileName, "path": asyncPath},
	})

	zipPath := filepath.Join(dir, "backup.zip")
	api := NewMobileAPI()
	out := api.ExportConfig(src, zipPath, string(extras))
	if strings.Contains(out, `"error"`) {
		t.Fatalf("export failed: %s", out)
	}

	in := api.ImportConfig(zipPath, dst, "")
	if strings.Contains(in, `"error"`) {
		t.Fatalf("import failed: %s", in)
	}
	for _, name := range []string{configFileName, certFileName, keyFileName, prefsFileName, asyncFileName} {
		if _, err := os.Stat(filepath.Join(dst, name)); err != nil {
			t.Errorf("expected %s in dst: %v", name, err)
		}
	}
	if !strings.Contains(in, `"importedPrefs":true`) {
		t.Errorf("expected importedPrefs:true in %s", in)
	}
	if !strings.Contains(in, `"importedAsync":true`) {
		t.Errorf("expected importedAsync:true in %s", in)
	}
}

func TestImportConfig_ForkBackup(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "dst")
	zipPath := filepath.Join(dir, "fork-backup.zip")

	entries := map[string][]byte{
		"config.xml":              []byte(minimalConfigXML),
		"cert.pem":                []byte(minimalCertPEM),
		"key.pem":                 []byte(minimalKeyPEM),
		"https-cert.pem":          []byte(minimalCertPEM),
		"https-key.pem":           []byte(minimalKeyPEM),
		"sharedpreferences.dat":   []byte{0xAC, 0xED, 0x00, 0x05},
		"index-v2/000001.log":     []byte("ignored"),
		"index-v2/CURRENT":        []byte("ignored"),
		"index-v2/sub/MANIFEST-1": []byte("ignored"),
	}
	writeBackupZip(t, zipPath, entries, false)

	api := NewMobileAPI()
	out := api.ImportConfig(zipPath, dst, "")
	if strings.Contains(out, `"error"`) {
		t.Fatalf("import failed: %s", out)
	}
	for _, name := range []string{configFileName, certFileName, keyFileName, httpsCertFileName, httpsKeyFileName} {
		if _, err := os.Stat(filepath.Join(dst, name)); err != nil {
			t.Errorf("expected %s after fork import: %v", name, err)
		}
	}
	if _, err := os.Stat(filepath.Join(dst, "index-v2")); err == nil {
		t.Error("index-v2/ should not be extracted")
	}
	if _, err := os.Stat(filepath.Join(dst, "sharedpreferences.dat")); err == nil {
		t.Error("sharedpreferences.dat should not be extracted")
	}
	if !strings.Contains(out, `"importedPrefs":false`) {
		t.Errorf("expected importedPrefs:false in %s", out)
	}
}

func TestImportConfig_EncryptedRoundTrip(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "dst")
	zipPath := filepath.Join(dir, "encrypted.zip")
	writeEncryptedZip(t, zipPath, map[string][]byte{
		"config.xml":         []byte(minimalConfigXML),
		"cert.pem":           []byte(minimalCertPEM),
		"key.pem":            []byte(minimalKeyPEM),
		"https-cert.pem":     []byte(minimalCertPEM),
		"index-v2/log.0":     []byte("ignored"),
	}, "topsecret")

	api := NewMobileAPI()

	out := api.ImportConfig(zipPath, dst, "")
	if !strings.Contains(out, "password required") {
		t.Errorf("expected password-required error, got %s", out)
	}

	out = api.ImportConfig(zipPath, dst, "wrong")
	if !strings.Contains(out, "wrong password") && !strings.Contains(out, "password") {
		t.Errorf("expected wrong-password error, got %s", out)
	}

	out = api.ImportConfig(zipPath, dst, "topsecret")
	if strings.Contains(out, `"error"`) {
		t.Fatalf("expected success with correct password, got %s", out)
	}
	for _, name := range []string{configFileName, certFileName, keyFileName, httpsCertFileName} {
		if _, err := os.Stat(filepath.Join(dst, name)); err != nil {
			t.Errorf("expected %s after encrypted import: %v", name, err)
		}
	}
}

func TestImportConfig_RejectsZipSlip(t *testing.T) {
	dir := t.TempDir()
	zipPath := filepath.Join(dir, "evil.zip")
	writeBackupZip(t, zipPath, map[string][]byte{
		"config.xml":          []byte(minimalConfigXML),
		"cert.pem":            []byte(minimalCertPEM),
		"key.pem":             []byte(minimalKeyPEM),
		"../../../etc/passwd": []byte("nope"),
	}, false)

	api := NewMobileAPI()
	out := api.ImportConfig(zipPath, filepath.Join(dir, "dst"), "")
	if !strings.Contains(out, "unsafe zip entry") {
		t.Errorf("expected zip-slip rejection, got %s", out)
	}
}

func TestImportConfig_MissingRequired(t *testing.T) {
	dir := t.TempDir()
	zipPath := filepath.Join(dir, "incomplete.zip")
	writeBackupZip(t, zipPath, map[string][]byte{
		"config.xml": []byte(minimalConfigXML),
		"cert.pem":   []byte(minimalCertPEM),
	}, false)

	api := NewMobileAPI()
	out := api.ImportConfig(zipPath, filepath.Join(dir, "dst"), "")
	if !strings.Contains(out, "archive missing") {
		t.Errorf("expected missing-file error, got %s", out)
	}
}

func TestImportConfig_RollbackOnInvalidConfig(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "dst")
	if err := os.MkdirAll(dst, 0o700); err != nil {
		t.Fatal(err)
	}
	for _, n := range backupFiles {
		if err := os.WriteFile(filepath.Join(dst, n), []byte("ORIGINAL-"+n), 0o600); err != nil {
			t.Fatal(err)
		}
	}

	zipPath := filepath.Join(dir, "bad-xml.zip")
	writeBackupZip(t, zipPath, map[string][]byte{
		"config.xml": []byte("not xml at all"),
		"cert.pem":   []byte(minimalCertPEM),
		"key.pem":    []byte(minimalKeyPEM),
	}, false)

	api := NewMobileAPI()
	out := api.ImportConfig(zipPath, dst, "")
	if !strings.Contains(out, "error") {
		t.Fatalf("expected validation error, got %s", out)
	}
	for _, n := range backupFiles {
		data, err := os.ReadFile(filepath.Join(dst, n))
		if err != nil {
			t.Fatalf("original %s missing after rollback: %v", n, err)
		}
		if string(data) != "ORIGINAL-"+n {
			t.Errorf("rollback lost original %s: got %q", n, data)
		}
	}
}
