from typing import List, Union

from fastapi import FastAPI
from fastembed import TextEmbedding
from pydantic import BaseModel


MODEL_NAME = "BAAI/bge-small-zh-v1.5"
model = TextEmbedding(model_name=MODEL_NAME)
app = FastAPI(title="SelfDesk local embeddings")


class EmbeddingRequest(BaseModel):
    input: Union[str, List[str]]
    model: str | None = None


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "dimensions": 512}


@app.post("/v1/embeddings")
@app.post("/embeddings")  # OpenAI 官方路径；Spring AI 默认带 /v1，部分客户端不带
def embeddings(request: EmbeddingRequest):
    texts = [request.input] if isinstance(request.input, str) else request.input
    vectors = [vector.tolist() for vector in model.embed(texts)]
    return {
        "object": "list",
        "model": request.model or MODEL_NAME,
        "data": [
            {"object": "embedding", "index": index, "embedding": vector}
            for index, vector in enumerate(vectors)
        ],
        "usage": {"prompt_tokens": 0, "total_tokens": 0},
    }
