import pytest

from tests.conftest import FakeBridge, make_pdf
from zibi.agent import Agent
from zibi.llm.base import LlmResponse, ToolCallRequest
from zibi.llm.fake import FakeLlm
from zibi.rag import DocumentStore, PdfProblem, chunk_text, extract_pages, ingest_pdf, search_terms


@pytest.fixture
def store(tmp_path):
    return DocumentStore(tmp_path / "t.db")


def test_short_text_is_one_chunk_and_blank_is_none():
    assert chunk_text("Hello   world.\n\nSecond line.") == ["Hello world. Second line."]
    assert chunk_text("   \n ") == []


def test_long_text_is_split_with_overlap_and_nothing_is_lost():
    sentences = [f"Sentence number {i} talks about topic {i}." for i in range(80)]
    chunks = chunk_text(" ".join(sentences), max_chars=300, overlap=80)
    assert len(chunks) > 5
    assert all(len(c) <= 300 + 80 for c in chunks)
    joined = " ".join(chunks)
    assert all(s in joined for s in sentences)
    # consecutive chunks share some text (overlap)
    assert any(chunks[i].split(". ")[-1][:15] in chunks[i + 1] for i in range(len(chunks) - 1))


def test_giant_unbroken_text_is_hard_split():
    chunks = chunk_text("x" * 5000, max_chars=1000, overlap=100)
    assert len(chunks) >= 5 and all(len(c) <= 1100 for c in chunks)


def test_search_terms_drop_filler_words():
    assert search_terms("What did the teacher say about gradient descent?") == ["gradient", "descent"]


def test_search_finds_the_right_passage_with_page_and_stemming(store):
    store.add_document("phone1", "ml.pdf", 2, [
        (1, "Gradient descent minimizes the loss function by following the slope."),
        (2, "Regularization adds a penalty to reduce overfitting in neural networks."),
    ])
    hits = store.search("phone1", "minimizing loss")
    assert hits and hits[0]["page"] == 1 and hits[0]["document"] == "ml.pdf"
    assert store.search("phone1", "overfit")[0]["page"] == 2
    assert store.search("phone1", "quantum entanglement") == []


def test_documents_are_private_to_their_owner(store):
    store.add_document("alice", "a.pdf", 1, [(1, "secret recipe for lasagna")])
    assert store.search("bob", "lasagna") == []
    assert store.list_documents("bob") == []
    assert store.delete_document("bob", store.list_documents("alice")[0]["id"]) is False
    assert store.search("alice", "lasagna")


def test_restrict_search_to_one_document_and_delete(store):
    store.add_document("u", "physics.pdf", 1, [(1, "Energy is conserved in a closed system.")])
    store.add_document("u", "chem.pdf", 1, [(1, "Energy changes during a reaction are measured in joules.")])
    both = store.search("u", "energy")
    assert {h["document"] for h in both} == {"physics.pdf", "chem.pdf"}
    only = store.search("u", "energy", document="chem")
    assert [h["document"] for h in only] == ["chem.pdf"]

    doc_id = [d for d in store.list_documents("u") if d["name"] == "chem.pdf"][0]["id"]
    assert store.delete_document("u", doc_id) is True
    assert {h["document"] for h in store.search("u", "energy")} == {"physics.pdf"}


def test_query_with_punctuation_cannot_break_the_search(store):
    store.add_document("u", "a.pdf", 1, [(1, "cats and dogs")])
    assert store.search("u", 'cats" OR NEAR( * ) -- ;') is not None  # must not raise


def test_pdf_roundtrip_extracts_pages_and_indexes(store):
    pdf = make_pdf(["Photosynthesis converts light into chemical energy.", "Mitochondria produce ATP."])
    pages = extract_pages(pdf)
    assert len(pages) == 2 and "Photosynthesis" in pages[0]
    info = ingest_pdf(store, "u", "/some/path/bio notes.pdf", pdf)
    assert info["name"] == "bio notes.pdf" and info["pages"] == 2
    assert store.search("u", "ATP")[0]["page"] == 2


def test_unreadable_and_textless_pdfs_give_clear_errors(store):
    with pytest.raises(PdfProblem, match="readable"):
        ingest_pdf(store, "u", "x.pdf", b"%PDF-not really")
    with pytest.raises(PdfProblem, match="no selectable text"):
        ingest_pdf(store, "u", "scan.pdf", make_pdf(["", ""]))


async def test_agent_searches_documents_and_gets_passages(store, registry, ctx):
    store.add_document("u", "ml.pdf", 1, [(4, "The learning rate controls the step size of gradient descent.")])
    ctx.owner, ctx.documents, ctx.document_names = "u", store, ["ml.pdf"]
    llm = FakeLlm([
        LlmResponse(tool_calls=[ToolCallRequest(id="c1", name="search_documents", arguments={"query": "learning rate"})]),
        LlmResponse(content="It controls the step size (ml.pdf, p.4)."),
    ])
    history = []
    out = await Agent(llm).run(FakeBridge(), registry, history, "what is the learning rate?", ctx)
    tool_msg = next(m for m in history if m["role"] == "tool")["content"]
    assert "step size" in tool_msg and '"page": 4' in tool_msg
    assert out.startswith("It controls")
    system = llm.calls[0]["messages"][0]["content"]
    assert "ml.pdf" in system and "propose_handoff" in system  # the model is told what exists and when to hand off


async def test_search_with_no_matches_tells_the_model(store, registry, ctx):
    ctx.owner, ctx.documents = "u", store
    llm = FakeLlm([
        LlmResponse(tool_calls=[ToolCallRequest(id="c1", name="search_documents", arguments={"query": "zebra"})]),
        LlmResponse(content="Not in your documents."),
    ])
    history = []
    await Agent(llm).run(FakeBridge(), registry, history, "zebra?", ctx)
    assert "nothing relevant" in next(m for m in history if m["role"] == "tool")["content"]
