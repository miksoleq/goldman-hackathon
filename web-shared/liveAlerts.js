export function newAlerts(previous, current) {
  if (previous === null) return [];
  const known = new Set(previous.map(alert => alert.alertId));
  return current.filter(alert => !known.has(alert.alertId));
}
