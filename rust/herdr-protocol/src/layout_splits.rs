//! Deserialization of `pane.layout` (`PaneLayoutSnapshot`) and BSP split handle derivation.

use serde::{Deserialize, Serialize};

use crate::{PaneSurfaceSplit, PaneSurfaceSplitDirection, SurfaceRect};

/// Cell rectangle reported by `pane.layout`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct PaneLayoutRect {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}

impl From<PaneLayoutRect> for SurfaceRect {
    fn from(rect: PaneLayoutRect) -> Self {
        Self {
            x: rect.x,
            y: rect.y,
            width: rect.width,
            height: rect.height,
        }
    }
}

impl From<SurfaceRect> for PaneLayoutRect {
    fn from(rect: SurfaceRect) -> Self {
        Self {
            x: rect.x,
            y: rect.y,
            width: rect.width,
            height: rect.height,
        }
    }
}

/// Direction of a split reported in `PaneLayoutSplit`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PaneLayoutSplitDirection {
    /// Horizontal split (left/right panes, vertical divider bar).
    #[serde(alias = "horizontal", alias = "Horizontal", alias = "Right")]
    Right,
    /// Vertical split (top/bottom panes, horizontal divider bar).
    #[serde(alias = "vertical", alias = "Vertical", alias = "Down")]
    Down,
}

impl From<PaneLayoutSplitDirection> for PaneSurfaceSplitDirection {
    fn from(direction: PaneLayoutSplitDirection) -> Self {
        match direction {
            PaneLayoutSplitDirection::Right => Self::Horizontal,
            PaneLayoutSplitDirection::Down => Self::Vertical,
        }
    }
}

/// Declarative BSP layout node (matches `layout.export` / `layout.apply`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum PaneLayoutNode {
    Pane {
        #[serde(default)]
        pane_id: Option<String>,
        #[serde(default)]
        cwd: Option<String>,
        #[serde(default)]
        label: Option<String>,
    },
    Split {
        direction: PaneLayoutSplitDirection,
        ratio: f32,
        first: Box<PaneLayoutNode>,
        second: Box<PaneLayoutNode>,
    },
}

/// Single pane entry in `PaneLayoutSnapshot`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PaneLayoutPane {
    pub pane_id: String,
    #[serde(default)]
    pub focused: bool,
    pub rect: PaneLayoutRect,
}

/// Single split entry in `PaneLayoutSnapshot`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PaneLayoutSplit {
    pub id: String,
    pub direction: PaneLayoutSplitDirection,
    pub ratio: f32,
    pub rect: PaneLayoutRect,
}

/// Snapshot returned by Herdr's `pane.layout` JSON-RPC method and `layout.updated` events.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct PaneLayoutSnapshot {
    #[serde(default)]
    pub workspace_id: String,
    #[serde(default)]
    pub tab_id: String,
    #[serde(default)]
    pub zoomed: bool,
    #[serde(default)]
    pub area: PaneLayoutRect,
    #[serde(default)]
    pub focused_pane_id: String,
    #[serde(default)]
    pub panes: Vec<PaneLayoutPane>,
    #[serde(default)]
    pub splits: Vec<PaneLayoutSplit>,
}

/// Parses a binary tree `path: Vec<bool>` from a `PaneLayoutSplit.id` string.
///
/// Supports both Herdr's `split_{idx}_{root|01...}` format and shorthand `s{idx}:{root|lr...}`:
/// - `"s0:root"` or `"split_0_root"` -> `[]`
/// - `"s1:lr"` or `"split_1_01"` -> `[false, true]`
/// - `"s2:rlr"` or `"split_2_101"` -> `[true, false, true]`
pub fn parse_split_path(id: &str) -> Vec<bool> {
    let suffix = id
        .rsplit_once(':')
        .map(|(_, s)| s)
        .or_else(|| id.rsplit_once('_').map(|(_, s)| s))
        .unwrap_or(id);

    if suffix.is_empty() || suffix.eq_ignore_ascii_case("root") {
        return Vec::new();
    }

    let mut path = Vec::with_capacity(suffix.len());
    for ch in suffix.chars() {
        match ch {
            '0' | 'l' | 'L' => path.push(false),
            '1' | 'r' | 'R' => path.push(true),
            _ => {}
        }
    }
    path
}

/// Derives interactive `PaneSurfaceSplit` divider handles from a `PaneLayoutSnapshot`.
///
/// When `snapshot.zoomed` is `true`, returns an empty vector because the focused pane
/// occupies the full tab viewport and split dividers are hidden.
pub fn derive_splits_from_layout(snapshot: &PaneLayoutSnapshot) -> Vec<PaneSurfaceSplit> {
    if snapshot.zoomed {
        return Vec::new();
    }

    snapshot
        .splits
        .iter()
        .filter_map(|split| {
            let area = SurfaceRect::from(split.rect);
            if area.width == 0 || area.height == 0 {
                return None;
            }
            let ratio = if split.ratio.is_finite() {
                split.ratio.clamp(0.1, 0.9)
            } else {
                0.5
            };
            let direction = PaneSurfaceSplitDirection::from(split.direction);
            let (pos, hit_rect) = match direction {
                PaneSurfaceSplitDirection::Horizontal => {
                    let first_w = ((area.width as f32) * ratio).round() as u16;
                    let pos = area.x.saturating_add(first_w);
                    let max_x = area.x.saturating_add(area.width.saturating_sub(1));
                    let hit_x = pos.min(max_x);
                    (
                        pos,
                        SurfaceRect {
                            x: hit_x,
                            y: area.y,
                            width: 1,
                            height: area.height,
                        },
                    )
                }
                PaneSurfaceSplitDirection::Vertical => {
                    let first_h = ((area.height as f32) * ratio).round() as u16;
                    let pos = area.y.saturating_add(first_h);
                    let max_y = area.y.saturating_add(area.height.saturating_sub(1));
                    let hit_y = pos.min(max_y);
                    (
                        pos,
                        SurfaceRect {
                            x: area.x,
                            y: hit_y,
                            width: area.width,
                            height: 1,
                        },
                    )
                }
            };
            Some(PaneSurfaceSplit {
                direction,
                pos,
                area,
                hit_rect,
                path: parse_split_path(&split.id),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_split_paths_from_both_shorthand_and_server_ids() {
        assert_eq!(parse_split_path("s0:root"), Vec::<bool>::new());
        assert_eq!(parse_split_path("split_0_root"), Vec::<bool>::new());
        assert_eq!(parse_split_path("s1:lr"), vec![false, true]);
        assert_eq!(parse_split_path("split_1_01"), vec![false, true]);
        assert_eq!(parse_split_path("s2:rlr"), vec![true, false, true]);
        assert_eq!(parse_split_path("split_2_101"), vec![true, false, true]);
    }

    #[test]
    fn derives_splits_and_collapses_when_zoomed() {
        let mut snapshot = PaneLayoutSnapshot {
            workspace_id: "w1".into(),
            tab_id: "w1:t1".into(),
            zoomed: false,
            area: PaneLayoutRect {
                x: 0,
                y: 0,
                width: 120,
                height: 40,
            },
            focused_pane_id: "w1:p1".into(),
            panes: vec![
                PaneLayoutPane {
                    pane_id: "w1:p1".into(),
                    focused: true,
                    rect: PaneLayoutRect {
                        x: 0,
                        y: 0,
                        width: 60,
                        height: 40,
                    },
                },
                PaneLayoutPane {
                    pane_id: "w1:p2".into(),
                    focused: false,
                    rect: PaneLayoutRect {
                        x: 60,
                        y: 0,
                        width: 60,
                        height: 20,
                    },
                },
                PaneLayoutPane {
                    pane_id: "w1:p3".into(),
                    focused: false,
                    rect: PaneLayoutRect {
                        x: 60,
                        y: 20,
                        width: 60,
                        height: 20,
                    },
                },
            ],
            splits: vec![
                PaneLayoutSplit {
                    id: "s0:root".into(),
                    direction: PaneLayoutSplitDirection::Right,
                    ratio: 0.5,
                    rect: PaneLayoutRect {
                        x: 0,
                        y: 0,
                        width: 120,
                        height: 40,
                    },
                },
                PaneLayoutSplit {
                    id: "s1:r".into(),
                    direction: PaneLayoutSplitDirection::Down,
                    ratio: 0.5,
                    rect: PaneLayoutRect {
                        x: 60,
                        y: 0,
                        width: 60,
                        height: 40,
                    },
                },
            ],
        };

        let splits = derive_splits_from_layout(&snapshot);
        assert_eq!(splits.len(), 2);
        assert_eq!(splits[0].direction, PaneSurfaceSplitDirection::Horizontal);
        assert_eq!(splits[0].pos, 60);
        assert_eq!(splits[0].path, Vec::<bool>::new());
        assert_eq!(
            splits[0].hit_rect,
            SurfaceRect {
                x: 60,
                y: 0,
                width: 1,
                height: 40,
            }
        );

        assert_eq!(splits[1].direction, PaneSurfaceSplitDirection::Vertical);
        assert_eq!(splits[1].pos, 20);
        assert_eq!(splits[1].path, vec![true]);
        assert_eq!(
            splits[1].hit_rect,
            SurfaceRect {
                x: 60,
                y: 20,
                width: 60,
                height: 1,
            }
        );

        snapshot.zoomed = true;
        assert!(derive_splits_from_layout(&snapshot).is_empty());
    }
}
