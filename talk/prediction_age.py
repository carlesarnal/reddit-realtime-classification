"""Analyze observed per-event timestamps; never invent a load measurement."""
import argparse
import csv
from datetime import datetime
import json


def analyze(rows):
    seen, results = set(), []
    for row in rows:
        identity = row['event_id']
        if identity in seen:
            raise ValueError('duplicate event identity')
        seen.add(identity)
        times = [datetime.fromisoformat(row[key]) for key in
                 ('ingress_time', 'processing_start', 'processing_end', 'query_visible')]
        if any(t.tzinfo is None for t in times) or times != sorted(times):
            raise ValueError('timestamps must be ordered and timezone-aware')
        queue, compute, visibility = [(b-a).total_seconds() for a, b in zip(times, times[1:])]
        results.append({'event_id': identity, 'queue_seconds': queue,
                        'processing_seconds': compute, 'visibility_seconds': visibility,
                        'prediction_age_seconds': sum((queue, compute, visibility))})
    if not results:
        raise ValueError('no observations')
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('csv_file', help='measured timestamps, not source creation times reused as ingress')
    args = parser.parse_args()
    with open(args.csv_file, newline='') as stream:
        print(json.dumps(analyze(csv.DictReader(stream)), indent=2))
