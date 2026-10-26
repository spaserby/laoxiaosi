"""Offline checks for the model runner; no real API or SSH credentials are loaded."""
import io
import json
import unittest
import urllib.error
from unittest.mock import patch

import attachment_quality_models as models


class ModelRunnerTest(unittest.TestCase):
    def test_identical_inputs_are_requested_once(self):
        jobs = [{"key": "synthetic-a"}, {"key": "synthetic-a"}, {"key": "synthetic-b"}]
        self.assertEqual([j["key"] for j in models.pending_jobs(jobs, {})], ["synthetic-a", "synthetic-b"])

    def test_successful_cache_is_reused_but_failed_job_retries(self):
        cache = {"synthetic-a": {"text": "cached"}, "synthetic-b": {"error": "Model HTTP status 503"}}
        jobs = [{"key": "synthetic-a"}, {"key": "synthetic-b"}]
        self.assertEqual(models.pending_jobs(jobs, cache), [{"key": "synthetic-b"}])

    def test_http_error_does_not_disclose_body_or_credential(self):
        synthetic = "dummy-sensitive-value"
        error = urllib.error.HTTPError("https://example.invalid", 401, synthetic, {}, io.BytesIO(synthetic.encode()))
        with patch.object(models.urllib.request, "urlopen", side_effect=error):
            with self.assertRaisesRegex(ValueError, "Model HTTP status 401") as caught:
                models.post("https://example.invalid", synthetic, {}, 1)
        self.assertNotIn(synthetic, str(caught.exception))

    def test_out_of_order_embedding_rows_are_associated_with_original_inputs(self):
        response = {"data": [{"index": 1, "embedding": [0.0] * 1024}, {"index": 0, "embedding": [1.0] * 1024}]}
        bundle = {"kind": "embedding", "model": "synthetic", "items": [
            {"key": "first", "role": "document", "input": "synthetic first"},
            {"key": "second", "role": "document", "input": "synthetic second"}]}
        measure = {"callId": "synthetic", "elapsedMs": 1, "usage": {}}
        output = io.StringIO()
        with patch.object(models, "environment", return_value={"DASHSCOPE_API_KEY": "dummy-key"}), \
                patch.object(models, "post", return_value=(response, measure)), patch("sys.stdout", output):
            models.worker(bundle, None)
        items = json.loads(output.getvalue())["items"]
        self.assertEqual(items[0]["key"], "first")
        self.assertEqual(items[0]["embedding"][0], 1.0)
        self.assertEqual(items[1]["embedding"][0], 0.0)


if __name__ == "__main__":
    unittest.main()
