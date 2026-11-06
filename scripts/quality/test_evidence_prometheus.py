import json
import tempfile
import unittest
import urllib.parse
from pathlib import Path
from unittest.mock import patch

import evidence


class PrometheusRangeTests(unittest.TestCase):
    def capture(self, fail_on=None):
        urls = []

        def fake_get_json(url, timeout=5):
            urls.append(url)
            if fail_on and fail_on in urllib.parse.unquote(url):
                raise OSError('prometheus down')
            return {'status': 'success', 'data': {'result': []}}

        directory = Path(tempfile.mkdtemp())
        with patch('evidence.get_json', side_effect=fake_get_json):
            evidence.prometheus_range(directory, 1_000_000, 1_180_000)
        return directory, [urllib.parse.unquote(url) for url in urls]

    def test_saves_bottleneck_timer_families_as_raw_series(self):
        directory, urls = self.capture()
        expected = {
            'hikari_timers': 'hikaricp_connections_(acquire|usage)_seconds_(count|sum|max)',
            'kafka_spring_timers': 'spring_kafka_(listener|template)_seconds_(count|sum|max)',
            'kafka_producer_client': 'kafka_producer_',
        }
        for name, fragment in expected.items():
            self.assertTrue((directory / f'prometheus-{name}.json').is_file(), name)
            self.assertTrue(any(fragment in url for url in urls), (name, fragment))

    def test_saves_metric_name_catalog_so_missing_names_are_visible(self):
        directory, urls = self.capture()
        catalog = json.loads((directory / 'prometheus-metric-names.json').read_text())
        self.assertEqual(catalog['status'], 'success')
        self.assertTrue(any('/api/v1/label/__name__/values' in url for url in urls))

    def test_catalog_failure_is_recorded_without_stopping_other_queries(self):
        directory, _ = self.capture(fail_on='__name__/values')
        catalog = json.loads((directory / 'prometheus-metric-names.json').read_text())
        self.assertIn('captureError', catalog)
        self.assertTrue((directory / 'prometheus-hikari_timers.json').is_file())


if __name__ == '__main__':
    unittest.main()
