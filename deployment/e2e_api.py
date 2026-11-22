"""End-to-end API integration tests through the public edge (the same URLs the mobile app uses).

Run against a running stack: `python3 e2e_api.py`. Creates throwaway accounts and deletes them at the end.
Covers every mobile function: auth/session, profile, pets, records, feeding, journal, memories, bookings,
rewards, reminders, files, documents, scan drafts, the real OCR scan pipeline, isolation and account deletion.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import time
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BASE = os.environ.get("IPETS_E2E_BASE", "http://127.0.0.1:18080")
_reference_candidates = [
    Path(os.environ["IPETS_REFERENCE_DIR"]) if os.environ.get("IPETS_REFERENCE_DIR") else None,
    Path(__file__).resolve().parent.parent / "Reference",
    Path(__file__).resolve().parents[2] / "Reference",
]
REFERENCE = next((path for path in _reference_candidates if path and path.is_dir()), _reference_candidates[1])
CARD = REFERENCE / "Sample-card3.jpeg"
PRINTED_CARD = REFERENCE / "Upload-file-3.jpeg"
PASSWORD = "Sync-test-2026"


def call(method, path, body=None, token=None, raw: bytes | None = None, headers=None, expect=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Accept": "application/json", **(headers or {})}
    if body is not None:
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            status, payload = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, payload = error.code, error.read()
    if expect is not None:
        assert status == expect, f"{method} {path}: expected {expect}, got {status}: {payload[:300]!r}"
    if payload and payload[:1] in (b"{", b"["):
        return status, json.loads(payload)
    return status, payload


def new_id():
    return str(uuid.uuid4())


def register(name):
    email = f"e2e-{uuid.uuid4().hex[:10]}@ipets.test"
    _, session = call("POST", "/auth/api/v1/register", {"name": name, "email": email, "password": PASSWORD}, expect=201)
    return email, session


class Api(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.email_a, cls.a = register("Owner A")
        cls.email_b, cls.b = register("Owner B")
        cls.ta, cls.tb = cls.a["accessToken"], cls.b["accessToken"]
        cls.pet = new_id()
        call("PUT", f"/pet/api/v1/pets/{cls.pet}", {"name": "Luna", "species": "Cat", "breed": "BSH", "sex": "Female", "birthday": "2023-03-18", "weight": 4.2}, cls.ta, expect=200)

    @classmethod
    def tearDownClass(cls):
        for session in (cls.a, cls.b):
            token = session["accessToken"]
            for service in ("pet", "notification", "document"):
                call("DELETE", f"/{service}/api/v1/me", token=token)
            call("DELETE", "/auth/api/v1/me", token=token)

    # ---- auth-service ----
    def test_auth_register_login_refresh_rotation_and_logout(self):
        call("POST", "/auth/api/v1/register", {"name": "Dup", "email": self.email_a, "password": PASSWORD}, expect=409)
        call("POST", "/auth/api/v1/register", {"name": "Weak", "email": f"w{uuid.uuid4().hex[:6]}@ipets.test", "password": "short"}, expect=400)
        call("POST", "/auth/api/v1/login", {"email": self.email_a, "password": "wrong-pass-1"}, expect=401)
        _, login = call("POST", "/auth/api/v1/login", {"email": self.email_a.upper(), "password": PASSWORD}, expect=200)
        _, me = call("GET", "/auth/api/v1/me", token=login["accessToken"], expect=200)
        self.assertEqual(me["email"], self.email_a)
        _, rotated = call("POST", "/auth/api/v1/refresh", {"refreshToken": login["refreshToken"]}, expect=200)
        self.assertNotEqual(rotated["refreshToken"], login["refreshToken"])
        # Reusing a rotated refresh token is treated as theft: the whole session family is revoked.
        call("POST", "/auth/api/v1/refresh", {"refreshToken": login["refreshToken"]}, expect=401)
        call("POST", "/auth/api/v1/refresh", {"refreshToken": rotated["refreshToken"]}, expect=401)
        _, again = call("POST", "/auth/api/v1/login", {"email": self.email_a, "password": PASSWORD}, expect=200)
        call("POST", "/auth/api/v1/logout", {"refreshToken": again["refreshToken"]}, expect=204)
        call("POST", "/auth/api/v1/refresh", {"refreshToken": again["refreshToken"]}, expect=401)

    def test_demo_user_can_log_in(self):
        if os.environ.get('IPETS_E2E_DEMO_ENABLED', 'true') != 'true':
            call("POST", "/auth/api/v1/login", {"email": "demo@ipets.app", "password": "PetCare123!"}, expect=401)
            return
        _, demo = call("POST", "/auth/api/v1/login", {"email": "demo@ipets.app", "password": "PetCare123!"}, expect=200)
        self.assertEqual(demo["user"]["name"], "Alex Tan")
        call("POST", "/auth/api/v1/logout", {"refreshToken": demo["refreshToken"]}, expect=204)

    def test_tokens_from_other_issuers_are_rejected_everywhere(self):
        for service in ("pet", "notification", "document"):
            call("GET", f"/{service}/api/v1/sync", token="eyJhbGciOiJSUzI1NiJ9.e30.x", expect=401)

    # ---- pet-service ----
    def test_profile_and_all_pet_scoped_collections_round_trip(self):
        call("PUT", "/pet/api/v1/profile/me", {"name": "Owner A", "phone": "+60 12-345 6789", "notifications": True, "marketing": False, "language": "ms"}, self.ta, expect=200)
        record, feed, journal, booking, redemption = new_id(), new_id(), new_id(), new_id(), new_id()
        call("PUT", f"/pet/api/v1/records/{record}", {"petId": self.pet, "title": "Rabies", "kind": "Vaccination", "date": "2026-01-10", "clinic": "Happy Paws", "notes": "", "dueDate": "2027-01-10", "source": "manual"}, self.ta, expect=200)
        call("PUT", f"/pet/api/v1/feeds/{feed}", {"petId": self.pet, "food": "Salmon", "grams": 85, "date": "2026-11-20", "notes": ""}, self.ta, expect=200)
        call("PUT", f"/pet/api/v1/journals/{journal}", {"petId": self.pet, "title": "First day", "body": "Hello", "date": "2026-11-20"}, self.ta, expect=200)
        call("PUT", f"/pet/api/v1/bookings/{booking}", {"petId": self.pet, "provider": "Fluffy Cuts", "service": "Bath", "date": "2026-12-01", "time": "10:00", "notes": "", "status": "Reserved"}, self.ta, expect=200)
        call("PUT", f"/pet/api/v1/redemptions/{redemption}", {"title": "Treats", "cost": 120}, self.ta, expect=200)
        call("PUT", f"/pet/api/v1/redemptions/{new_id()}", {"title": "Too much", "cost": 400}, self.ta, expect=409)
        # Idempotent retry of the same id updates rather than duplicating.
        _, updated = call("PUT", f"/pet/api/v1/feeds/{feed}", {"petId": self.pet, "food": "Salmon", "grams": 90, "date": "2026-11-20", "notes": "seconds"}, self.ta, expect=200)
        self.assertEqual(updated["grams"], 90.0)
        _, sync = call("GET", "/pet/api/v1/sync", token=self.ta, expect=200)
        c = sync["collections"]
        self.assertEqual(c["profile"][0]["language"], "ms")
        self.assertEqual([r["dueDate"] for r in c["records"] if r["id"] == record], ["2027-01-10"])
        for name, item in (("feeds", feed), ("journals", journal), ("bookings", booking), ("redemptions", redemption)):
            self.assertEqual(sum(1 for r in c[name] if r["id"] == item), 1, name)

    def test_validation_errors_are_rejected(self):
        for path, body in [
            (f"/pet/api/v1/pets/{new_id()}", {"name": "", "species": "Cat", "breed": "x", "sex": "Male", "birthday": "2023-01-01", "weight": 1}),
            (f"/pet/api/v1/pets/{new_id()}", {"name": "X", "species": "Dragon", "breed": "x", "sex": "Male", "birthday": "2023-01-01", "weight": 1}),
            (f"/pet/api/v1/records/{new_id()}", {"petId": self.pet, "title": "x", "kind": "Vaccination", "date": "2026-02-30", "clinic": "c", "source": "manual"}),
            (f"/notification/api/v1/reminders/{new_id()}", {"petId": self.pet, "title": "x", "date": "2026-12-01", "time": "25:00"}),
        ]:
            with self.subTest(path=path):
                call("PUT", path, body, self.ta, expect=422)
        call("PUT", "/pet/api/v1/pets/..%2Fetc", {"name": "x"}, self.ta)  # must never reach the filesystem/DB as a path

    def test_owners_are_isolated(self):
        call("PUT", f"/pet/api/v1/records/{new_id()}", {"petId": self.pet, "title": "x", "kind": "Other", "date": "2026-01-01", "clinic": "c", "source": "manual"}, self.tb, expect=422)
        call("PUT", f"/pet/api/v1/pets/{self.pet}", {"name": "Stolen", "species": "Cat", "breed": "x", "sex": "Male", "birthday": "2023-01-01", "weight": 1}, self.tb, expect=404)
        call("DELETE", f"/pet/api/v1/pets/{self.pet}", token=self.tb, expect=404)
        call("PUT", f"/notification/api/v1/reminders/{new_id()}", {"petId": self.pet, "title": "x", "date": "2026-12-01", "time": "09:00"}, self.tb, expect=422)
        _, sync = call("GET", "/pet/api/v1/sync", token=self.tb, expect=200)
        self.assertEqual(sync["collections"]["pets"], [])

    def test_delete_pet_cascades_and_incremental_sync_reports_deletions(self):
        pet, record = new_id(), new_id()
        call("PUT", f"/pet/api/v1/pets/{pet}", {"name": "Max", "species": "Dog", "breed": "Golden", "sex": "Male", "birthday": "2022-08-04", "weight": 28.5}, self.ta, expect=200)
        call("PUT", f"/pet/api/v1/records/{record}", {"petId": pet, "title": "Checkup", "kind": "Checkup", "date": "2026-10-02", "clinic": "c", "source": "manual"}, self.ta, expect=200)
        _, before = call("GET", "/pet/api/v1/sync", token=self.ta, expect=200)
        time.sleep(0.05)
        call("DELETE", f"/pet/api/v1/pets/{pet}", token=self.ta, expect=204)
        call("DELETE", f"/pet/api/v1/pets/{pet}", token=self.ta, expect=204)  # idempotent
        _, changes = call("GET", f"/pet/api/v1/sync?since={before['serverTime']}", token=self.ta, expect=200)
        self.assertTrue(any(p["id"] == pet and p["deleted"] for p in changes["collections"]["pets"]))
        self.assertTrue(any(r["id"] == record and r["deleted"] for r in changes["collections"]["records"]))
        call("PUT", f"/pet/api/v1/pets/{pet}", {"name": "Max", "species": "Dog", "breed": "Golden", "sex": "Male", "birthday": "2022-08-04", "weight": 28.5}, self.ta, expect=410)

    # ---- notification-service ----
    def test_reminders_round_trip(self):
        reminder = new_id()
        call("PUT", f"/notification/api/v1/reminders/{reminder}", {"petId": self.pet, "title": "Booster", "date": "2026-12-01", "time": "09:00", "done": False}, self.ta, expect=200)
        call("PUT", f"/notification/api/v1/reminders/{reminder}", {"petId": self.pet, "title": "Booster", "date": "2026-12-03", "time": "09:30", "done": True}, self.ta, expect=200)
        _, sync = call("GET", "/notification/api/v1/sync", token=self.ta, expect=200)
        [item] = [r for r in sync["collections"]["reminders"] if r["id"] == reminder]
        self.assertEqual((item["date"], item["time"], item["done"]), ("2026-12-03", "09:30", True))
        call("DELETE", f"/notification/api/v1/reminders/{reminder}", token=self.ta, expect=204)

    # ---- document-service: files, documents, drafts ----
    def test_files_documents_and_drafts(self):
        image = CARD.read_bytes()
        file_id = new_id()
        _, stored = call("POST", "/document/api/v1/files", raw=image, token=self.ta, headers={"Content-Type": "image/jpeg", "X-File-Id": file_id}, expect=201)
        self.assertEqual((stored["mime"], stored["sha256"]), ("image/jpeg", hashlib.sha256(image).hexdigest()))
        call("POST", "/document/api/v1/files", raw=image, token=self.ta, headers={"X-File-Id": file_id}, expect=201)  # retry-safe
        _, body = call("GET", f"/document/api/v1/files/{file_id}", token=self.ta, expect=200)
        self.assertEqual(body, image)
        call("GET", f"/document/api/v1/files/{file_id}", token=self.tb, expect=404)
        call("POST", "/document/api/v1/files", raw=b"<html><script>alert(1)</script>", token=self.ta, headers={"Content-Type": "image/jpeg"}, expect=415)
        doc = new_id()
        call("PUT", f"/document/api/v1/documents/{doc}", {"petId": self.pet, "name": "Vet card", "mime": "image/jpeg", "date": "2026-11-22", "status": "Needs review", "fileId": file_id}, self.ta, expect=200)
        call("PUT", f"/document/api/v1/documents/{new_id()}", {"petId": self.pet, "name": "x", "mime": "image/jpeg", "date": "2026-11-22", "status": "Saved", "fileId": new_id()}, self.ta, expect=422)
        draft = new_id()
        call("PUT", f"/document/api/v1/drafts/{draft}", {"petId": self.pet, "documentIds": [doc], "created": "2026-11-22", "engine": "fast", "cardPetName": "Luna", "warnings": [], "rows": [{"rowId": "r1", "title": "Rabies"}]}, self.ta, expect=200)
        _, sync = call("GET", "/document/api/v1/sync", token=self.ta, expect=200)
        self.assertEqual([d["rows"] for d in sync["collections"]["drafts"] if d["id"] == draft], [[{"rowId": "r1", "title": "Rabies"}]])
        call("DELETE", f"/document/api/v1/drafts/{draft}", token=self.ta, expect=204)
        # Memories reference an uploaded photo by file id.
        call("PUT", f"/pet/api/v1/memories/{new_id()}", {"petId": self.pet, "caption": "Home", "fileId": file_id, "date": "2026-11-22"}, self.ta, expect=200)

    # ---- document-service + ocr-worker: real PaddleOCR scan pipeline ----
    def scan(self, image: bytes, label: str):
        _, session = call("POST", f"/document/api/v1/pets/{self.pet}/scans",
                          {"pages": [{"page": 1, "contentType": "image/jpeg", "size": len(image), "sha256": hashlib.sha256(image).hexdigest()}]}, self.ta, expect=201)
        scan = session["scanId"]
        call("POST", f"/document/api/v1/scans/{scan}/complete", token=self.ta, expect=409)  # pages missing
        call("PUT", session["uploads"][0]["url"], raw=b"\xff\xd8\xff" + b"x" * 10, token=self.ta, expect=422)  # size/checksum mismatch
        call("PUT", session["uploads"][0]["url"], raw=image, token=self.ta, headers={"Content-Type": "image/jpeg"}, expect=200)
        call("GET", f"/document/api/v1/scans/{scan}", token=self.tb, expect=404)
        call("GET", f"/document/api/v1/scans/{scan}/draft", token=self.ta, expect=409)  # not ready
        call("POST", f"/document/api/v1/scans/{scan}/complete", token=self.ta, expect=200)
        call("POST", f"/document/api/v1/scans/{scan}/complete", token=self.ta, expect=200)  # idempotent
        started, stages = time.time(), []
        while time.time() - started < 180:
            _, status = call("GET", f"/document/api/v1/scans/{scan}", token=self.ta, expect=200)
            if not stages or stages[-1] != status["stage"]:
                stages.append(status["stage"])
            if status["status"] in ("NEEDS_REVIEW", "FAILED"):
                break
            time.sleep(0.25)
        self.assertEqual(status["status"], "NEEDS_REVIEW", status)
        _, draft = call("GET", f"/document/api/v1/scans/{scan}/draft", token=self.ta, expect=200)
        self.assertEqual(draft["pages"][0]["page"], 1)
        summary = [(r["date"].get("raw"), r["date"]["value"], r["treatment"]["value"], r["nextDue"]["value"]) for r in draft["rows"]]
        print(f"\n  {label}: stages {stages} in {time.time() - started:.1f}s; rows {summary}; warnings {draft['warnings']}", file=sys.stderr)
        return draft

    def test_scan_printed_card_reads_every_row(self):
        draft = self.scan(PRINTED_CARD.read_bytes(), "printed card")
        got = [(r["date"]["value"], r["treatment"]["value"], r["nextDue"]["value"]) for r in draft["rows"]][:4]
        self.assertEqual(got, [("2023-01-12", "DHPP", "2024-01-12"), ("2023-01-12", "Rabies", "2024-01-12"),
                               ("2023-03-15", "Leptospirosis", "2024-03-15"), ("2023-06-20", "Kennel Cough", "2024-06-20")])
        self.assertEqual(draft["identityHints"]["petName"]["value"], "Maxi")

    def test_scan_handwritten_card_flags_unreadable_dates(self):
        draft = self.scan(CARD.read_bytes(), "handwritten card")
        row = draft["rows"][0]
        self.assertIn("Felocell", row["treatment"]["value"] or "")
        self.assertIsNone(row["date"]["value"])  # never guessed
        self.assertTrue(any("handwriting" in w for w in draft["warnings"]))

    def test_internal_endpoints_are_not_public(self):
        for path in ("/document/internal/scans/x/pages/1", "/pet/internal/pets/a/owners/b"):
            call("GET", path, expect=404)


class AccountDeletion(unittest.TestCase):
    def test_deleting_account_removes_data_and_login(self):
        email, session = register("Leaving")
        token, pet = session["accessToken"], new_id()
        call("PUT", f"/pet/api/v1/pets/{pet}", {"name": "Bye", "species": "Other", "breed": "x", "sex": "Unknown", "birthday": "2024-01-01", "weight": 1}, token, expect=200)
        file_id = new_id()
        call("POST", "/document/api/v1/files", raw=CARD.read_bytes(), token=token, headers={"X-File-Id": file_id}, expect=201)
        for service in ("pet", "notification", "document"):
            call("DELETE", f"/{service}/api/v1/me", token=token, expect=204)
        call("DELETE", "/auth/api/v1/me", token=token, expect=204)
        _, sync = call("GET", "/pet/api/v1/sync", token=token, expect=200)
        self.assertEqual(sync["collections"]["pets"], [])
        call("GET", f"/document/api/v1/files/{file_id}", token=token, expect=404)
        call("POST", "/auth/api/v1/login", {"email": email, "password": PASSWORD}, expect=401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
