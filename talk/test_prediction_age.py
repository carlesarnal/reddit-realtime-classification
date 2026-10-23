import unittest
from prediction_age import analyze


class AgeTest(unittest.TestCase):
    def row(self):
        return dict(event_id='fixture', ingress_time='2026-10-23T10:00:00+00:00',
                    processing_start='2026-10-23T10:00:02+00:00',
                    processing_end='2026-10-23T10:00:05+00:00',
                    query_visible='2026-10-23T10:00:06+00:00')

    def test_boundaries(self):
        result = analyze([self.row()])[0]
        self.assertEqual([result[k] for k in ('queue_seconds', 'processing_seconds',
                                            'visibility_seconds', 'prediction_age_seconds')], [2, 3, 1, 6])

    def test_bad_clock(self):
        row = self.row()
        row['processing_end'] = '2026-10-23T09:00:00+00:00'
        with self.assertRaises(ValueError):
            analyze([row])

    def test_duplicate_and_empty(self):
        for rows in ([], [self.row(), self.row()]):
            with self.assertRaises(ValueError):
                analyze(rows)
