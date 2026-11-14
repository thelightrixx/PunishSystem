import argparse
import hashlib
import json
import shutil
import subprocess
import tarfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory


MODEL_URL = (
    "https://huggingface.co/mlc-ai/Qwen3.5-0.8B-q4f16_1-MLC/"
    "resolve/0ec138972555613c1d7812a821778ad0398c8790/"
)
WASM_URL = (
    "https://raw.githubusercontent.com/mlc-ai/binary-mlc-llm-libs/"
    "146ed9cd4b4062e967b017fd2291f7106b50599f/web-llm-models/"
    "v0_2_87/base/Qwen3.5-0.8B-q4f16_1_cs1k-webgpu.wasm"
)
RUNTIME_URL = "https://registry.npmjs.org/@mlc-ai/web-llm/-/web-llm-0.2.88.tgz"
LICENSE_URL = "https://huggingface.co/Qwen/Qwen3.5-0.8B/resolve/2fc06364715b967f1860aea9cf38778875588b17/LICENSE"

MODEL_HASHES = {
    "mlc-chat-config.json": "1ea93c2b8b396a3754516d0c4b6f4b6881c2930ca2cc6cb2cf00e511e6be86a9",
    "params_shard_0.bin": "414681bc0134320f15d4591521588dd88ae97e6e6a92242a1c2900d6548e99f5",
    "params_shard_1.bin": "f23ba01d17f1eaee9f557f48b65bbba80a272e522e96076052037139e58bcce1",
    "params_shard_2.bin": "98cf1af3ce6740e1ef8dd9a0148c767c3b0e831e59d140bfada52afa57bb4184",
    "params_shard_3.bin": "c76000a164a9893f81ff678bf80a90bcba6d00a450f81a7a52f1c1804b99bde4",
    "params_shard_4.bin": "0079356e628317d9bf5d63a297b06c4240470edbb6caa3dbf49daceedeeecf72",
    "params_shard_5.bin": "55391999df3b47f96cffe523783092ca3981a088fe1f4602b4270156f8975d3f",
    "params_shard_6.bin": "44ec47561a8b0e71569fcb6c3995bea48257b6e1f64f838d0e42b7d06953b44b",
    "params_shard_7.bin": "3e299baff5304ad5a7c767a4f8048e850d3cd4d8afc99e86e4daf64708c81ba5",
    "params_shard_8.bin": "944ef481f34d54251c7b8408a1ea319a74982abef4535361ecaf7c20bbda228f",
    "params_shard_9.bin": "4913895152c4d17032eca8f7dbbd7adc8324802ec99db4d41eb7c98a5cadf706",
    "params_shard_10.bin": "97d2931620ee85c76d2520cec5dab1a56c4061680aae1d46dd52608296211314",
    "tensor-cache.json": "bab74fa529512ca7641f6a4aea0baeab26742d28886e79aac11b09fa8738c0df",
    "tokenizer.json": "5f9e4d4901a92b997e463c1f46055088b6cca5ca61a6522d1b9f64c4bb81cb42",
}
RUNTIME_HASHES = {
    "web-llm.mjs": "5460309cbb350e97fe63c817d73718588976049e7125ef66b657818a0e31818a",
    "web-llm-LICENSE.txt": "87935f332472db09253871326ef23d5d6a45f4fd2e6c96772f72052866dfab0d",
}
WASM_HASH = "39623bb05581e04616debfd762b8ea0d0f2d641b3afa8ac1df902aac0dfdb04e"


def valid(path, expected):
    if not path.is_file():
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == expected


def fetch(url, path, expected=None):
    if expected and valid(path, expected):
        return
    staging = path.with_suffix(path.suffix + ".part")
    subprocess.run(
        ["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "3",
         "--connect-timeout", "20", "--max-time", "180", "--output", str(staging), url],
        check=True,
    )
    if expected and not valid(staging, expected):
        raise ValueError(f"Falsche SHA-256-Prüfsumme für {path.name}")
    staging.replace(path)


def prepare(directory):
    model = directory / "qwen" / "resolve" / "main"
    model.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=6) as executor:
        files = [executor.submit(fetch, MODEL_URL + name, model / name, digest)
                 for name, digest in MODEL_HASHES.items()]
        files.append(executor.submit(fetch, WASM_URL, directory / "qwen.wasm", WASM_HASH))
        files.append(executor.submit(fetch, LICENSE_URL, directory / "Qwen-LICENSE.txt",
                                     "bbedc3fda3305820b977265f01b8619d87570a6739de3a5582c3464840f1e57a"))
        if not all(valid(directory / name, digest) for name, digest in RUNTIME_HASHES.items()):
            with TemporaryDirectory() as temporary:
                archive = Path(temporary) / "web-llm.tgz"
                fetch(RUNTIME_URL, archive)
                with tarfile.open(archive) as bundle:
                    for name, member in [("web-llm.mjs", "package/lib/index.js"),
                                         ("web-llm-LICENSE.txt", "package/LICENSE")]:
                        with bundle.extractfile(member) as stream, (directory / name).open("wb") as output:
                            shutil.copyfileobj(stream, output)
                        if not valid(directory / name, RUNTIME_HASHES[name]):
                            raise ValueError(f"Falsche SHA-256-Prüfsumme für {name}")
        for future in files:
            future.result()
    manifest = json.loads((model / "tensor-cache.json").read_text(encoding="utf-8"))
    for record in manifest["records"]:
        path = model / record["dataPath"]
        if path.name not in MODEL_HASHES or path.stat().st_size != record["nbytes"]:
            raise ValueError(f"Unvollständiger Gewichtssplit {path.name}")
    print(f"{sum(path.stat().st_size for path in directory.rglob('*') if path.is_file()):,} Bytes für den lokalen Doku-Chat")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path, help="Chat-Modellordner im Pages-Artefakt")
    prepare(parser.parse_args().directory)
