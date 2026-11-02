from fastapi.testclient import TestClient

from tests.conftest import make_pdf
from zibi.config import Settings
from zibi.llm.fake import FakeLlm
from zibi.rag import DocumentStore
from zibi.server import create_app

H = {"X-Zibi-Device": "phone1"}


def client(tmp_path, token=None):
    store = DocumentStore(tmp_path / "api.db")
    app = create_app(llm=FakeLlm([]), settings=Settings(dev_token=token), store=store)
    return TestClient(app), store


def test_upload_list_search_delete(tmp_path):
    c, store = client(tmp_path)
    with c:
        pdf = make_pdf(["Backpropagation computes gradients layer by layer.", "Dropout randomly disables neurons."])
        r = c.post("/documents", params={"name": "dl.pdf"}, content=pdf, headers=H)
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["pages"] == 2 and body["name"] == "dl.pdf"

        assert [d["name"] for d in c.get("/documents", headers=H).json()["documents"]] == ["dl.pdf"]
        assert store.search("phone1", "dropout")[0]["page"] == 2
        # another device sees nothing
        assert c.get("/documents", headers={"X-Zibi-Device": "other"}).json()["documents"] == []

        assert c.delete(f"/documents/{body['doc_id']}", headers=H).status_code == 200
        assert store.search("phone1", "dropout") == []
        assert c.delete(f"/documents/{body['doc_id']}", headers=H).status_code == 404


def test_rejects_non_pdf_scans_and_missing_device(tmp_path):
    c, _ = client(tmp_path)
    with c:
        r = c.post("/documents", params={"name": "a.txt"}, content=b"hello", headers=H)
        assert r.status_code == 422 and "PDF" in r.json()["detail"]
        r = c.post("/documents", params={"name": "scan.pdf"}, content=make_pdf(["", ""]), headers=H)
        assert r.status_code == 422 and "selectable text" in r.json()["detail"]
        r = c.post("/documents", params={"name": "a.pdf"}, content=make_pdf(["hello world text here"]))
        assert r.status_code == 422  # missing X-Zibi-Device header


def test_token_is_enforced_on_rest_too(tmp_path):
    c, _ = client(tmp_path, token="secret")
    with c:
        pdf = make_pdf(["Some real text on this page for indexing."])
        assert c.post("/documents", params={"name": "a.pdf"}, content=pdf, headers=H).status_code == 401
        ok = c.post("/documents", params={"name": "a.pdf"}, content=pdf, headers={**H, "X-Zibi-Token": "secret"})
        assert ok.status_code == 200
