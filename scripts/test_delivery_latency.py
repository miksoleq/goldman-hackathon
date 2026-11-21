import unittest

from delivery_latency import percentile


class PercentileTest(unittest.TestCase):
    def test_nearest_rank_for_thirty_receipts(self):
        values = list(reversed(range(1, 31)))
        self.assertEqual(percentile(values, .5), 15)
        self.assertEqual(percentile(values, .95), 29)

    def test_one_receipt(self):
        self.assertEqual(percentile([12.5], .95), 12.5)


if __name__ == "__main__":
    unittest.main()
