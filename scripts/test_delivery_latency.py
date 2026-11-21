import unittest

from delivery_latency import mark_observed, percentile


class PercentileTest(unittest.TestCase):
    def test_nearest_rank_for_thirty_receipts(self):
        values = list(reversed(range(1, 31)))
        self.assertEqual(percentile(values, .5), 15)
        self.assertEqual(percentile(values, .95), 29)

    def test_one_receipt(self):
        self.assertEqual(percentile([12.5], .95), 12.5)


class ReceiptTest(unittest.TestCase):
    def test_each_view_requires_its_own_first_visible_match(self):
        panel, monitor = {}, {}
        submitted = {"sent-1", "sent-2"}
        mark_observed(panel, ["warmup", "unrelated"], submitted, 10)
        mark_observed(monitor, ["sent-1"], submitted, 11)
        mark_observed(monitor, ["sent-1", "sent-2"], submitted, 12)
        self.assertEqual(panel, {})
        self.assertEqual(monitor, {"sent-1": 11, "sent-2": 12})


if __name__ == "__main__":
    unittest.main()
