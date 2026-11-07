package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fakePanel is a minimal Remnawave API: one user with two devices.
func fakePanel(t *testing.T) *httptest.Server {
	devices := []map[string]any{
		{"hwid": "hw-phone", "platform": "Android", "osVersion": "16", "deviceModel": "Pixel 9", "userAgent": "IYKYK/0.1.0 (Android 16)", "createdAt": "2026-10-01T10:00:00Z", "updatedAt": "2026-11-04T10:00:00Z"},
		{"hwid": "hw-old", "platform": "iOS", "osVersion": "18", "deviceModel": "iPhone", "userAgent": "Happ/3.0", "createdAt": "2026-01-01T10:00:00Z", "updatedAt": "2026-02-01T10:00:00Z"},
	}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer secret" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch {
		case r.URL.Path == "/api/users/by-short-uuid/abcdef12":
			_, _ = w.Write([]byte(`{"response":{"id":7,"uuid":"u-7","shortUuid":"abcdef12","hwidDeviceLimit":3}}`))
		case r.URL.Path == "/api/hwid/devices/7":
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]any{"total": len(devices), "devices": devices}})
		case r.URL.Path == "/api/hwid/devices/delete" && r.Method == "POST":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["userId"] != float64(7) {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			for i, d := range devices {
				if d["hwid"] == body["hwid"] {
					devices = append(devices[:i], devices[i+1:]...)
					break
				}
			}
			_, _ = w.Write([]byte(`{"response":{"total":0,"devices":[]}}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
}

func TestDevicesAPI(t *testing.T) {
	remna := fakePanel(t)
	defer remna.Close()
	p := &panel{base: remna.URL, token: "secret", client: remna.Client()}
	names := t.TempDir() + "/names.json"
	srv := httptest.NewServer(newHandler(config{fallbackLimit: 5, namesFile: names}, p))
	defer srv.Close()

	get := func() devicesResponse {
		req, _ := http.NewRequest("GET", srv.URL+"/iykyk/api/devices", nil)
		req.Header.Set("X-Subscription", "abcdef12")
		req.Header.Set("X-Hwid", "hw-phone")
		resp, err := http.DefaultClient.Do(req)
		if err != nil || resp.StatusCode != 200 {
			t.Fatalf("get: %v %v", err, resp.Status)
		}
		defer resp.Body.Close()
		var out devicesResponse
		_ = json.NewDecoder(resp.Body).Decode(&out)
		return out
	}
	out := get()
	if out.Used != 2 || out.Limit != 3 || !out.Devices[0].Current || out.Devices[1].Current {
		t.Fatalf("%+v", out)
	}
	if strings.Contains(out.Devices[0].ID, "hw-") || out.Devices[1].App != "Happ/3.0" {
		t.Fatalf("raw hwid leaked or bad app: %+v", out.Devices)
	}

	post := func(path, body, hwid string) int {
		req, _ := http.NewRequest("POST", srv.URL+path, strings.NewReader(body))
		req.Header.Set("X-Subscription", "abcdef12")
		if hwid != "" {
			req.Header.Set("X-Hwid", hwid)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}

	// Naming: from a device of the subscription only; trimmed to one short line.
	nameBody := `{"id":"` + out.Devices[1].ID + `","name":"  Мамин iPhone\nс очень длинным названием, которое не влезет никуда  "}`
	if code := post("/iykyk/api/devices/name", nameBody, ""); code != http.StatusForbidden {
		t.Fatalf("name without a device: %d", code)
	}
	if code := post("/iykyk/api/devices/name", nameBody, "hw-stranger"); code != http.StatusForbidden {
		t.Fatalf("name from a stranger: %d", code)
	}
	if code := post("/iykyk/api/devices/name", nameBody, "hw-phone"); code != 200 {
		t.Fatalf("name: %d", code)
	}
	named := get().Devices[1].Name
	if !strings.HasPrefix(named, "Мамин iPhone с очень") || len([]rune(named)) > maxNameRunes || strings.Contains(named, "\n") {
		t.Fatalf("name: %q", named)
	}
	// Survives a restart.
	if loadNames(names).get(nameKey(&panelUser{ID: 7}, "hw-old")) != named {
		t.Fatal("name not saved to the file")
	}

	if code := post("/iykyk/api/devices/delete", `{"id":"`+out.Devices[1].ID+`"}`, ""); code != http.StatusForbidden {
		t.Fatalf("delete without a device: %d", code)
	}
	if code := post("/iykyk/api/devices/delete", `{"id":"`+out.Devices[1].ID+`"}`, "hw-phone"); code != 200 {
		t.Fatalf("delete: %d", code)
	}
	if loadNames(names).get(nameKey(&panelUser{ID: 7}, "hw-old")) != "" {
		t.Fatal("name of a deleted device kept")
	}
	if out := get(); out.Used != 1 {
		t.Fatalf("after delete: %+v", out)
	}

	req, _ := http.NewRequest("GET", srv.URL+"/iykyk/api/devices", nil)
	req.Header.Set("X-Subscription", "nope!!")
	resp, _ := http.DefaultClient.Do(req)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("bad id: %s", resp.Status)
	}
}
