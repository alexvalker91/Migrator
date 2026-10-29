<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>@@TITLE@@</title>
<style>
/* __CODEMIE_CSS__ */
.charts { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: var(--space-4); }
.chart-box { position: relative; height: 260px; }
.bar { height: 8px; border-radius: var(--radius-sm); background: var(--color-bg-tertiary); }
.bar > span { display: block; height: 100%; border-radius: var(--radius-sm); background: var(--color-info); }
.summary { margin: 0; padding-left: var(--space-5); line-height: 1.6; }
details > summary { cursor: pointer; }
.detail-body { margin-top: var(--space-2); display: grid; gap: var(--space-2); }
.attempt, .evidence { padding: var(--space-3); border: 1px solid var(--color-border-structural); border-radius: var(--radius-lg); }
.reasoning { margin-top: var(--space-2); padding-left: var(--space-3); border-left: 3px solid var(--color-purple); }
pre { white-space: pre-wrap; word-break: break-word; max-height: 320px; overflow: auto; font-size: var(--text-xs); }
.evidence img { max-width: 100%; border: 1px solid var(--color-border-structural); border-radius: var(--radius-lg); }
code { font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: var(--text-xs); }
@media (max-width: 640px) { .chart-box { height: 220px; } }
</style>
<script>
if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) {
  document.documentElement.classList.add('light');
}
</script>
</head>
<body class="p-6">
<div class="container">
@@BODY@@
</div>
<script>
@@CHART_JS@@
</script>
<script>
const REPORT = @@DATA@@;
(function () {
  const css = getComputedStyle(document.documentElement);
  const token = (name, fallback) => (css.getPropertyValue(name).trim() || fallback);
  Chart.defaults.color = token('--color-text-muted', '#9a9a9a');
  Chart.defaults.borderColor = token('--color-border-structural', '#333436');
  Chart.defaults.font.family = 'Inter, system-ui, sans-serif';
  Chart.defaults.maintainAspectRatio = false;
  const palette = ['#2297F6', '#C084FC', '#259F4C', '#F5A534', '#F9303C', '#06B6D4'];
  const statusColors = { STABLE_PASS: '#259F4C', HEALED_AND_PASSED: '#C084FC', NEEDS_MANUAL_FIX: '#F9303C',
    PLATFORM_SPECIFIC: '#6b7280', SKIPPED_ALREADY_VERIFIED: '#06B6D4', PENDING: '#F5A534', IN_PROGRESS: '#2297F6' };
  const make = (id, config) => { const el = document.getElementById(id); if (el) { new Chart(el, config); } };
  const hasData = values => values.some(v => v > 0);

  const t = REPORT.tokens;
  make('chartTokens', { type: 'bar', data: { labels: t.labels, datasets: [
    { label: 'Input', data: t.input, backgroundColor: palette[0] },
    { label: 'Output', data: t.output, backgroundColor: palette[2] },
    { label: 'Reasoning', data: t.reasoning, backgroundColor: palette[1] } ] },
    options: { scales: { x: { stacked: true }, y: { stacked: true } } } });

  make('chartCost', { type: 'bar', data: { labels: REPORT.cost.labels, datasets: [
    { label: 'Cost, $', data: REPORT.cost.values, backgroundColor: palette[3] } ] },
    options: { plugins: { legend: { display: false } } } });

  if (hasData(REPORT.stages.values)) {
    make('chartStages', { type: 'doughnut', data: { labels: REPORT.stages.labels, datasets: [
      { data: REPORT.stages.values, backgroundColor: palette, borderWidth: 0 } ] },
      options: { plugins: { tooltip: { callbacks: { label: c => c.label + ': ' + Math.round(c.parsed / 60 * 10) / 10 + ' min' } } } } });
  }

  make('chartStatuses', { type: 'doughnut', data: { labels: REPORT.statuses.labels, datasets: [
    { data: REPORT.statuses.values, backgroundColor: REPORT.statuses.labels.map(l => statusColors[l] || '#999'), borderWidth: 0 } ] } });

  make('chartSavings', { type: 'bar', data: { labels: REPORT.savings.labels, datasets: [
    { label: 'Tokens saved', data: REPORT.savings.values, backgroundColor: palette[2] } ] },
    options: { plugins: { legend: { display: false } } } });

  const p = REPORT.projection;
  if (p.labels.length) {
    make('chartProjectionCost', { type: 'bar', data: { labels: p.labels, datasets: [
      { label: 'Total cost, $', data: p.cost, backgroundColor: palette[3] } ] },
      options: { plugins: { legend: { display: false } } } });
    make('chartProjectionTime', { type: 'bar', data: { labels: p.labels, datasets: p.devices.map((d, i) => (
      { label: d.devices + (d.devices === 1 ? ' device' : ' devices'), data: d.hours, backgroundColor: palette[i % palette.length] })) },
      options: { scales: { y: { title: { display: true, text: 'hours' } } } } });
  }
  const l = REPORT.learning;
  if (l.labels.length) {
    make('chartLearning', { type: 'line', data: { labels: l.labels, datasets: [
      { label: '$ per test', data: l.cost, borderColor: palette[3], backgroundColor: palette[3], yAxisID: 'y', tension: 0.25 },
      { label: 'Cache hit rate, %', data: l.hitRate, borderColor: palette[0], backgroundColor: palette[0], yAxisID: 'y1', tension: 0.25 } ] },
      options: { scales: { y: { position: 'left', title: { display: true, text: '$ per test' } },
        y1: { position: 'right', min: 0, max: 100, grid: { drawOnChartArea: false }, title: { display: true, text: '%' } } } } });
  }
})();
</script>
</body>
</html>
