"""Optional live acceptance check: python3 scripts/verify-rag-query.py [base-url]."""

import json
from pathlib import Path
import re
import sys
import urllib.request


def ask(base_url, question):
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/rag/query",
        data=json.dumps({"question": question}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=150) as response:
        return json.load(response)


def verify(base_url):
    result = ask(base_url, "what does error E204 mean")
    assert result["insufficientEvidence"] is False, result
    answer = result["answer"].lower()
    assert all(term in answer for term in ("e204", "temperature", "sensor")), result
    assert any(term in answer for term in ("unavailable", "missing", "invalid")), result
    reference = (Path(__file__).resolve().parents[1] / "docs/equipment/fault-code-reference.md").read_text()
    citations = [c for c in result["citations"] if c["documentId"] == "REF-FAULTS-001"]
    assert citations, result
    assert any("e204" in c["quote"].lower() for c in citations), result
    for citation in citations:
        assert citation["source"] == "fault-code-reference.md", citation
        # Ingestion prepends the source title to each section. Reconstruct that cited context.
        title = re.search(r"(?m)^# (.+)$", reference).group(0)
        section = re.search(r"(?ms)^## " + re.escape(citation["section"]) + r"\n(.*?)(?=^## |\Z)", reference)
        assert section is not None, citation
        passage = title + "\n\n" + section.group(0)
        assert " ".join(citation["quote"].split()) in " ".join(passage.split()), citation
    unknown = ask(base_url, "what does error E999 mean")
    assert unknown["insufficientEvidence"] is True and unknown["citations"] == [], unknown
    print(json.dumps({"known_code": result, "unknown_code": unknown}, indent=2))


if __name__ == "__main__":
    verify(sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080")
