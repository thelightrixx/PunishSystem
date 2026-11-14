import argparse
import json
import re
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


class Documentation(HTMLParser):
    def __init__(self):
        super().__init__()
        self.page = False
        self.fragments = []

    def handle_starttag(self, tag, attrs):
        if tag == "section" and dict(attrs).get("class") == "page":
            self.page = True

    def handle_endtag(self, tag):
        if tag == "section":
            self.page = False

    def handle_data(self, data):
        if self.page:
            self.fragments.append(data.lower().translate(str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"})))


def embed(terms, model_dir):
    tokenizer = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
    tokenizer.enable_padding(pad_id=1, pad_token="<pad>")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    runtime = ort.InferenceSession(str(model_dir / "onnx" / "model_int8.onnx"), options, providers=["CPUExecutionProvider"])
    for start in range(0, len(terms), 64):
        batch = tokenizer.encode_batch(["query: " + term for term in terms[start:start + 64]])
        mask = np.array([item.attention_mask for item in batch], dtype=np.int64)
        ids = np.array([item.ids for item in batch], dtype=np.int64)
        hidden, = runtime.run(None, {"input_ids": ids, "attention_mask": mask, "token_type_ids": np.zeros_like(ids)})
        mean = (hidden * mask[..., None]).sum(axis=1) / mask.sum(axis=1, keepdims=True)
        yield mean / np.linalg.norm(mean, axis=1, keepdims=True)


def related_terms(terms, vectors, corpus):
    scores = vectors @ vectors.T
    pairs = {}
    for i, term in enumerate(terms):
        nearest = sorted((j for j in range(len(terms)) if i != j and terms[j] in corpus), key=lambda j: -scores[i, j])[:4]
        for j in nearest:
            similarity = round(float(scores[i, j]) * 100)
            if similarity >= 88 and not (term.startswith(terms[j]) or terms[j].startswith(term)):
                pairs[tuple(sorted((term, terms[j])))] = similarity
    return [[left, right, score] for (left, right), score in sorted(pairs.items())]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path, help="Ordner mit Tokenizer und ONNX-INT8-Export von multilingual-e5-small")
    model = parser.parse_args().model
    root = Path(__file__).resolve().parent.parent
    docs = Documentation()
    docs.feed((root / "docs/index.html").read_text(encoding="utf-8"))
    counts = Counter(re.findall(r"[a-z][a-z0-9]{3,}", "\n".join(docs.fragments)))
    corpus = {term for term, count in counts.items() if 2 <= count <= 85}
    variants = set(("ausloggen eintreten verknuepfen verzweigen ausweichloesung abgleichen sperrgrund ausschalten einschalten loeschfrist "
                    "ausrollen nachziehen abmelden wegnehmen freischalten ablaufen dauerhaft verteilen ankuendigung vereinheitlichen "
                    "ablaufdatum stummschalten schreibsperre dateisicherung fehlermeldungen fehlerdiagnose widerspruch lokalisieren "
                    "verlust instanz aufbewahrung belastung benachrichtigung entziehen berechtigung umziehen verlagern").split())
    terms = sorted(corpus | variants)
    vectors = np.concatenate(list(embed(terms, model)))
    pairs = related_terms(terms, vectors, corpus)
    header = ("// Wortähnlichkeiten aus multilingual-e5-small (MIT), erzeugt mit scripts/doku-suchdaten.py.\n"
              "// https://huggingface.co/intfloat/multilingual-e5-small\n"
              "// ONNX: https://huggingface.co/Xenova/multilingual-e5-small/tree/761b726dd34fb83930e26aab4e9ac3899aa1fa78\n")
    rows = ",\n".join("  " + json.dumps(pair, separators=(",", ":")) for pair in pairs)
    (root / "docs/doc-search-data.js").write_text(
        header + "window.PunishSearchData = [\n" + rows + "\n];\n", encoding="utf-8", newline="\n"
    )
    print(f"{len(pairs)} Wortpaare für {len(terms)} Begriffe")


if __name__ == "__main__":
    main()
