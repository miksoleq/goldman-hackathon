FROM python:3.13-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends libseccomp2 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py runner_harness.py ./
USER nobody
CMD ["python", "-B", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001", "--limit-concurrency", "8", "--no-access-log"]
