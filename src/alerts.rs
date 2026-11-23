//! Alert rules, hysteresis and disk-capacity prediction.
//!
//! The agent evaluates rules on every tick. A rule only produces an event on a
//! state transition, and recovery needs to cross the threshold by a margin, so
//! a metric hovering around the limit cannot spam the history.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::collect::Sample;
use crate::store::{now_ts, Store};

pub const MAX_RULES: usize = 20;
/// Percentage points, days or degrees that recovery must cross.
pub const HYSTERESIS: f64 = 2.0;
const PREDICT_WINDOW_SECS: i64 = 7 * 86_400;
const PREDICT_MIN_POINTS: usize = 10;
const PREDICT_MIN_SPAN_SECS: i64 = 3_600;
const EVAL_EVERY: Duration = Duration::from_secs(5);
const PREDICT_EVERY: Duration = Duration::from_secs(60);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Disk,
    Memory,
    Temperature,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Disk => "disk",
            Kind::Memory => "memory",
            Kind::Temperature => "temperature",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "disk" => Some(Kind::Disk),
            "memory" => Some(Kind::Memory),
            "temperature" => Some(Kind::Temperature),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Rule {
    pub id: String,
    pub kind: Kind,
    pub mount: Option<String>,
    pub percent: Option<f64>,
    pub days: Option<f64>,
    pub celsius: Option<f64>,
    pub silence_until: i64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RuleInput {
    pub id: String,
    pub kind: Kind,
    #[serde(default)]
    pub mount: Option<String>,
    #[serde(default)]
    pub percent: Option<f64>,
    #[serde(default)]
    pub days: Option<f64>,
    #[serde(default)]
    pub celsius: Option<f64>,
}

/// Validates the closed rule shape and rejects fields that do not belong to
/// the selected kind.
pub fn valid_rule_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 32
        && id
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_')
}

pub fn parse_rule(input: &RuleInput) -> Result<Rule, String> {
    if !valid_rule_id(&input.id) {
        return Err("id must be 1..32 chars of [a-z0-9_-]".into());
    }

    let mount = match input.mount.as_deref() {
        Some(mount) => {
            if mount.is_empty()
                || mount.len() > 128
                || !mount.starts_with('/')
                || mount.chars().any(char::is_control)
            {
                return Err("mount must be an absolute path up to 128 chars".into());
            }
            Some(mount.to_string())
        }
        None => None,
    };

    if let Some(percent) = input.percent {
        if !(0.0..=100.0).contains(&percent) || percent == 0.0 {
            return Err("percent must be in (0, 100]".into());
        }
    }
    if let Some(days) = input.days {
        if !(0.0..=3650.0).contains(&days) || days == 0.0 {
            return Err("days must be in (0, 3650]".into());
        }
    }
    if let Some(celsius) = input.celsius {
        if !(-40.0..=150.0).contains(&celsius) {
            return Err("celsius must be in [-40, 150]".into());
        }
    }

    match input.kind {
        Kind::Disk => {
            if input.celsius.is_some() {
                return Err("celsius does not apply to a disk rule".into());
            }
            if input.percent.is_none() && input.days.is_none() {
                return Err("a disk rule needs percent, days or both".into());
            }
        }
        Kind::Memory => {
            if input.mount.is_some() || input.days.is_some() || input.celsius.is_some() {
                return Err("a memory rule only accepts percent".into());
            }
            if input.percent.is_none() {
                return Err("a memory rule needs percent".into());
            }
        }
        Kind::Temperature => {
            if input.mount.is_some() || input.percent.is_some() || input.days.is_some() {
                return Err("a temperature rule only accepts celsius".into());
            }
            if input.celsius.is_none() {
                return Err("a temperature rule needs celsius".into());
            }
        }
    }

    Ok(Rule {
        id: input.id.clone(),
        kind: input.kind,
        mount,
        percent: input.percent,
        days: input.days,
        celsius: input.celsius,
        silence_until: 0,
    })
}

#[derive(Debug, Clone, Default)]
pub struct Measurement {
    pub percent: Option<f64>,
    pub celsius: Option<f64>,
    pub days: Option<f64>,
    pub mount: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Transition {
    Fire,
    Recover,
}

/// Pure state machine. `firing` is the state before this sample.
pub fn transition(rule: &Rule, m: &Measurement, firing: bool) -> Option<Transition> {
    let mut bad = false;
    let mut all_recovered = true;
    let mut present = false;

    if let (Some(threshold), Some(value)) = (rule.percent, m.percent) {
        present = true;
        bad |= value > threshold;
        all_recovered &= value < threshold - HYSTERESIS;
    }
    if let (Some(threshold), Some(value)) = (rule.days, m.days) {
        present = true;
        bad |= value < threshold;
        all_recovered &= value > threshold + HYSTERESIS;
    }
    if let (Some(threshold), Some(value)) = (rule.celsius, m.celsius) {
        present = true;
        bad |= value > threshold;
        all_recovered &= value < threshold - HYSTERESIS;
    }

    if !present {
        return None;
    }
    if firing {
        (all_recovered && !bad).then_some(Transition::Recover)
    } else {
        bad.then_some(Transition::Fire)
    }
}

pub fn measure(
    rule: &Rule,
    sample: &Sample,
    predictions: &HashMap<String, Option<f64>>,
) -> Option<Measurement> {
    match rule.kind {
        Kind::Disk => {
            let disks: Vec<_> = sample
                .disks
                .iter()
                .filter(|disk| {
                    rule.mount
                        .as_deref()
                        .is_none_or(|mount| disk.mount == mount)
                })
                .collect();
            if disks.is_empty() {
                return None;
            }

            let mut percent = None;
            let mut percent_mount = None;
            for disk in &disks {
                if disk.total == 0 {
                    continue;
                }
                let value = disk.used as f64 / disk.total as f64 * 100.0;
                if percent.is_none_or(|current| value > current) {
                    percent = Some(value);
                    percent_mount = Some(disk.mount.clone());
                }
            }

            let mut days = None;
            let mut days_mount = None;
            for disk in &disks {
                if let Some(Some(value)) = predictions.get(&disk.mount) {
                    if days.is_none_or(|current| *value < current) {
                        days = Some(*value);
                        days_mount = Some(disk.mount.clone());
                    }
                }
            }

            Some(Measurement {
                percent,
                celsius: None,
                days,
                mount: percent_mount.or(days_mount),
            })
        }
        Kind::Memory => {
            let total = sample.mem.total;
            if total == 0 {
                return None;
            }
            Some(Measurement {
                percent: Some(sample.mem.used as f64 / total as f64 * 100.0),
                celsius: None,
                days: None,
                mount: None,
            })
        }
        Kind::Temperature => {
            let celsius = sample
                .temps
                .iter()
                .map(|temp| temp.celsius as f64)
                .fold(None, |max: Option<f64>, value| {
                    Some(max.map_or(value, |current| current.max(value)))
                })?;
            Some(Measurement {
                percent: None,
                celsius: Some(celsius),
                days: None,
                mount: None,
            })
        }
    }
}

fn event_values(rule: &Rule, m: &Measurement) -> (Option<f64>, Option<f64>) {
    if let Some(threshold) = rule.percent {
        return (m.percent, Some(threshold));
    }
    if let Some(threshold) = rule.days {
        return (m.days, Some(threshold));
    }
    if let Some(threshold) = rule.celsius {
        return (m.celsius, Some(threshold));
    }
    (None, None)
}

fn event_detail(rule: &Rule, m: &Measurement) -> String {
    let where_ = m
        .mount
        .as_deref()
        .map(|mount| format!(" on {mount}"))
        .unwrap_or_default();
    match rule.kind {
        Kind::Disk => format!(
            "disk{where_}: {:.1}% used, {} days left",
            m.percent.unwrap_or(0.0),
            m.days
                .map(|days| format!("{days:.1}"))
                .unwrap_or_else(|| "?".into())
        ),
        Kind::Memory => format!("memory: {:.1}% used", m.percent.unwrap_or(0.0)),
        Kind::Temperature => format!("temperature: {:.1} °C", m.celsius.unwrap_or(0.0)),
    }
}

/// Evaluates every rule against one sample and persists transitions.
pub struct Evaluator {
    last_eval: Instant,
    last_prediction: Instant,
    predictions: HashMap<String, Option<f64>>,
}

impl Default for Evaluator {
    fn default() -> Self {
        Self::new()
    }
}

impl Evaluator {
    pub fn new() -> Self {
        let now = Instant::now();
        Self {
            last_eval: now - EVAL_EVERY,
            last_prediction: now - PREDICT_EVERY,
            predictions: HashMap::new(),
        }
    }

    pub fn tick(&mut self, store: &Store, sample: &Sample) {
        if self.last_eval.elapsed() < EVAL_EVERY {
            return;
        }
        self.last_eval = Instant::now();
        let now = now_ts();

        if self.last_prediction.elapsed() >= PREDICT_EVERY {
            self.predictions = prediction_map(store, now);
            self.last_prediction = Instant::now();
        }

        let rules = store.list_alert_rules();
        if rules.is_empty() {
            return;
        }
        let firing: HashSet<String> = store.firing_rule_ids();

        for rule in &rules {
            let Some(measurement) = measure(rule, sample, &self.predictions) else {
                continue;
            };
            let was_firing = firing.contains(&rule.id);
            let transition = transition(rule, &measurement, was_firing);

            if now < rule.silence_until {
                // A recovery during silence is applied silently so the rule
                // is fresh when the silence ends.
                if was_firing && transition == Some(Transition::Recover) {
                    store.set_alert_firing(&rule.id, false, now);
                }
                continue;
            }

            match transition {
                Some(Transition::Fire) => {
                    let (value, threshold) = event_values(rule, &measurement);
                    if let Err(e) = store.insert_alert_event(
                        &rule.id,
                        rule.kind,
                        now,
                        "fired",
                        value,
                        threshold,
                        Some(&event_detail(rule, &measurement)),
                    ) {
                        tracing::warn!(error = %e, "cannot store alert event");
                    }
                    store.set_alert_firing(&rule.id, true, now);
                    tracing::info!(rule = %rule.id, kind = rule.kind.as_str(), "alert fired");
                }
                Some(Transition::Recover) => {
                    let (value, threshold) = event_values(rule, &measurement);
                    if let Err(e) = store.insert_alert_event(
                        &rule.id,
                        rule.kind,
                        now,
                        "recovered",
                        value,
                        threshold,
                        Some(&event_detail(rule, &measurement)),
                    ) {
                        tracing::warn!(error = %e, "cannot store alert event");
                    }
                    store.set_alert_firing(&rule.id, false, now);
                    tracing::info!(rule = %rule.id, kind = rule.kind.as_str(), "alert recovered");
                }
                None => {}
            }
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Prediction {
    pub mount: String,
    pub days_left: f64,
}

pub fn predictions(store: &Store, now: i64) -> Vec<Prediction> {
    prediction_map(store, now)
        .into_iter()
        .filter_map(|(mount, days)| days.map(|days_left| Prediction { mount, days_left }))
        .collect()
}

pub fn prediction_map(store: &Store, now: i64) -> HashMap<String, Option<f64>> {
    store
        .disk_mounts()
        .into_iter()
        .map(|mount| {
            let points = store.disk_usage_since(&mount, now - PREDICT_WINDOW_SECS);
            (mount, predict_days(&points))
        })
        .collect()
}

/// Least-squares slope over the available window. A flat or shrinking series
/// predicts nothing, which is what keeps a stable disk from alerting.
pub fn predict_days(points: &[(i64, i64, i64)]) -> Option<f64> {
    if points.len() < PREDICT_MIN_POINTS {
        return None;
    }
    let (first_t, _, _) = *points.first()?;
    let (last_t, used, total) = *points.last()?;
    if last_t - first_t < PREDICT_MIN_SPAN_SECS {
        return None;
    }

    let n = points.len() as f64;
    let (mut sx, mut sy, mut sxx, mut sxy) = (0.0, 0.0, 0.0, 0.0);
    for (t, used, _) in points {
        let x = (*t - first_t) as f64 / 86_400.0;
        let y = *used as f64;
        sx += x;
        sy += y;
        sxx += x * x;
        sxy += x * y;
    }
    let denominator = n * sxx - sx * sx;
    if denominator.abs() < f64::EPSILON {
        return None;
    }
    let slope_per_day = (n * sxy - sx * sy) / denominator;
    if slope_per_day <= 0.0 {
        return None;
    }
    let free = (total - used).max(0) as f64;
    let days = free / slope_per_day;
    days.is_finite().then_some(days.min(9_999.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(percent: f64) -> Rule {
        Rule {
            id: "mem".into(),
            kind: Kind::Memory,
            mount: None,
            percent: Some(percent),
            days: None,
            celsius: None,
            silence_until: 0,
        }
    }

    fn memory(value: f64) -> Measurement {
        Measurement {
            percent: Some(value),
            ..Measurement::default()
        }
    }

    #[test]
    fn oscillation_around_the_threshold_fires_once() {
        let rule = rule(85.0);
        let mut firing = false;
        let mut fires = 0;
        for value in [84.0, 86.0, 84.0, 86.0, 84.0, 85.9] {
            if let Some(t) = transition(&rule, &memory(value), firing) {
                match t {
                    Transition::Fire => {
                        fires += 1;
                        firing = true;
                    }
                    Transition::Recover => firing = false,
                }
            }
        }
        assert_eq!(fires, 1);
        assert!(firing);

        // Recovery needs a full 2-point margin, not just crossing back.
        assert_eq!(transition(&rule, &memory(84.0), true), None);
        assert_eq!(
            transition(&rule, &memory(82.9), true),
            Some(Transition::Recover)
        );
    }

    #[test]
    fn flat_or_shrinking_disk_never_predicts_days() {
        let base = 1_700_000_000;
        let flat: Vec<_> = (0..60).map(|i| (base + i * 60, 500, 1000)).collect();
        assert_eq!(predict_days(&flat), None);

        let shrinking: Vec<_> = (0..60).map(|i| (base + i * 60, 500 - i, 1000)).collect();
        assert_eq!(predict_days(&shrinking), None);

        let short: Vec<_> = (0..5).map(|i| (base + i * 60, 500 + i, 1000)).collect();
        assert_eq!(predict_days(&short), None);
    }

    #[test]
    fn linear_fill_predicts_within_twenty_percent() {
        let base = 1_700_000_000;
        let used_per_min: i64 = 10_000;
        let total: i64 = 1_000_000_000;
        let start: i64 = 100_000_000;
        // 24 h of one-minute samples: 14.4 MB/day, (total-start)/slope.
        let points: Vec<_> = (0..1440)
            .map(|i| (base + i * 60, start + i * used_per_min, total))
            .collect();
        let expected =
            (total - (start + 1439 * used_per_min)) as f64 / (used_per_min * 1440) as f64;
        let got = predict_days(&points).unwrap();
        assert!(
            (got - expected).abs() / expected < 0.20,
            "expected ~{expected}, got {got}"
        );
    }

    #[test]
    fn rule_validation_is_closed_by_kind() {
        let base = RuleInput {
            id: "disk-root".into(),
            kind: Kind::Disk,
            mount: Some("/".into()),
            percent: Some(85.0),
            days: Some(7.0),
            celsius: None,
        };
        assert!(parse_rule(&base).is_ok());

        let mut bad = base.clone();
        bad.kind = Kind::Memory;
        assert!(parse_rule(&bad).is_err());

        let mut bad = base.clone();
        bad.id = "Disk Root".into();
        assert!(parse_rule(&bad).is_err());

        let mut bad = base.clone();
        bad.percent = Some(0.0);
        assert!(parse_rule(&bad).is_err());

        let mut bad = base;
        bad.celsius = Some(70.0);
        assert!(parse_rule(&bad).is_err());

        let temp = RuleInput {
            id: "cpu".into(),
            kind: Kind::Temperature,
            mount: None,
            percent: None,
            days: None,
            celsius: Some(85.0),
        };
        assert!(parse_rule(&temp).is_ok());
    }
}
