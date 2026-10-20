//! Synthesizes standard `herdr_protocol::PaneSurfaceFrame` values from a Mosh `ghostty_vt::Terminal`
//! framebuffer and Herdr's `pane.layout` (`PaneLayoutSnapshot`) geometry.

use ghostty_vt::{
    ActiveScreen, CellColor, CellStyle, CellWide, CursorVisualStyle, RenderState, RowCells,
    RowIterator, Terminal,
};
use herdr_protocol::{
    derive_splits_from_layout, modifier_with_underline_style, pack_indexed_color, pack_rgb_color,
    CellData, CursorState, FrameData, PaneLayoutSnapshot, PaneSurfaceFrame, PaneSurfacePane,
    SurfaceGraphicsScene, SurfaceRect, COLOR_RESET, MODIFIER_BOLD, MODIFIER_CROSSED_OUT,
    MODIFIER_DIM, MODIFIER_HIDDEN, MODIFIER_ITALIC, MODIFIER_REVERSED, MODIFIER_SLOW_BLINK,
    MODIFIER_UNDERLINED,
};

use crate::mosh_ssp::PredictiveEchoEngine;

/// Computes the Mosh PTY height in rows for a given Zen Canvas `viewport_rows` and `tab_count`.
///
/// Per Part IV.1.3 of the design specification, when `tab_count > 1`, unmodified `herdr` renders
/// a 1-row top tab bar (`ui.tab_bar_position = "top"`, `ui.hide_tab_bar_when_single_tab = true`).
/// Requesting `viewport_rows + 1` on the Mosh PTY and stripping row `0` during synthesis dedicates
/// 100% of the `viewport_rows` to pure terminal pane cells.
pub const fn pty_rows_for_viewport(viewport_rows: u16, tab_count: usize) -> u16 {
    if tab_count > 1 {
        viewport_rows.saturating_add(1)
    } else {
        viewport_rows
    }
}

fn convert_cell_color(color: Option<CellColor>) -> u32 {
    match color {
        None => COLOR_RESET,
        Some(CellColor::Palette(idx)) => pack_indexed_color(idx),
        Some(CellColor::Rgb(rgb)) => pack_rgb_color(rgb.r, rgb.g, rgb.b),
    }
}

fn convert_style_modifiers(style: &CellStyle) -> u16 {
    let mut mods = 0u16;
    if style.bold {
        mods |= MODIFIER_BOLD;
    }
    if style.faint {
        mods |= MODIFIER_DIM;
    }
    if style.italic {
        mods |= MODIFIER_ITALIC;
    }
    if style.underlined || style.underline != 0 {
        mods |= MODIFIER_UNDERLINED;
        mods = modifier_with_underline_style(mods, style.underline);
    }
    if style.blink {
        mods |= MODIFIER_SLOW_BLINK;
    }
    if style.inverse {
        mods |= MODIFIER_REVERSED;
    }
    if style.invisible {
        mods |= MODIFIER_HIDDEN;
    }
    if style.strikethrough {
        mods |= MODIFIER_CROSSED_OUT;
    }
    mods
}

fn cursor_shape_param(style: CursorVisualStyle, blinking: bool) -> u8 {
    match (style, blinking) {
        (CursorVisualStyle::Block, true) => 1,
        (CursorVisualStyle::Block | CursorVisualStyle::BlockHollow, false) => 2,
        (CursorVisualStyle::Underline, true) => 3,
        (CursorVisualStyle::Underline, false) => 4,
        (CursorVisualStyle::Bar, true) => 5,
        (CursorVisualStyle::Bar, false) => 6,
        (CursorVisualStyle::BlockHollow, true) => 1,
    }
}

/// Bridges a Mosh `ghostty_vt::Terminal` instance and `PaneLayoutSnapshot` into `PaneSurfaceFrame`.
pub struct VtSynthesizer {
    terminal: Terminal,
    render_state: RenderState,
    row_iterator: RowIterator,
    row_cells: RowCells,
    cols: u16,
    viewport_rows: u16,
    tab_count: usize,
}

impl VtSynthesizer {
    pub fn new(
        cols: u16,
        viewport_rows: u16,
        tab_count: usize,
        max_scrollback: usize,
    ) -> Result<Self, String> {
        let pty_rows = pty_rows_for_viewport(viewport_rows, tab_count);
        let terminal = Terminal::new(cols, pty_rows, max_scrollback).map_err(|e| e.to_string())?;
        let render_state = RenderState::new().map_err(|e| e.to_string())?;
        let row_iterator = RowIterator::new().map_err(|e| e.to_string())?;
        let row_cells = RowCells::new().map_err(|e| e.to_string())?;
        Ok(Self {
            terminal,
            render_state,
            row_iterator,
            row_cells,
            cols,
            viewport_rows,
            tab_count,
        })
    }

    pub fn cols(&self) -> u16 {
        self.cols
    }

    pub fn viewport_rows(&self) -> u16 {
        self.viewport_rows
    }

    pub fn pty_rows(&self) -> u16 {
        pty_rows_for_viewport(self.viewport_rows, self.tab_count)
    }

    /// Feeds raw ANSI/VT bytes from the Mosh UDP SSP stream into `libghostty-vt`.
    pub fn feed_vt_bytes(&mut self, bytes: &[u8]) {
        self.terminal.write(bytes);
    }

    /// Resizes the Mosh VT terminal, automatically adding 1 top row when `tab_count > 1`.
    pub fn resize(
        &mut self,
        cols: u16,
        viewport_rows: u16,
        tab_count: usize,
        cell_width_px: u32,
        cell_height_px: u32,
    ) -> Result<(), String> {
        let pty_rows = pty_rows_for_viewport(viewport_rows, tab_count);
        self.terminal
            .resize(cols, pty_rows, cell_width_px, cell_height_px)
            .map_err(|e| e.to_string())?;
        self.cols = cols;
        self.viewport_rows = viewport_rows;
        self.tab_count = tab_count;
        Ok(())
    }

    /// Synthesizes a complete `PaneSurfaceFrame` from the current `libghostty-vt` state,
    /// optional `PaneLayoutSnapshot`, and optional `PredictiveEchoEngine`.
    pub fn synthesize_frame(
        &mut self,
        boot_id: &str,
        projection_revision: u64,
        surface_revision: u64,
        layout: Option<&PaneLayoutSnapshot>,
        predictive_echo: Option<&PredictiveEchoEngine>,
    ) -> Result<PaneSurfaceFrame, String> {
        self.render_state
            .update(&self.terminal)
            .map_err(|e| e.to_string())?;

        let strip_top_row = self.tab_count > 1;
        let skip_rows = usize::from(strip_top_row);
        let out_width = self.cols;
        let out_height = self.viewport_rows;
        let total_cells = usize::from(out_width) * usize::from(out_height);
        let mut cells = Vec::with_capacity(total_cells);

        let mut scratch_bytes = Vec::with_capacity(16);
        let mut scratch_text = String::with_capacity(8);

        {
            let mut row_iter = self
                .render_state
                .populate_row_iterator(&mut self.row_iterator)
                .map_err(|e| e.to_string())?;

            let mut pty_row_idx = 0usize;
            while row_iter.next() {
                if pty_row_idx < skip_rows {
                    pty_row_idx += 1;
                    continue;
                }
                let out_row = pty_row_idx - skip_rows;
                if out_row >= usize::from(out_height) {
                    break;
                }
                pty_row_idx += 1;

                let mut cell_iter = row_iter
                    .populate_cells(&mut self.row_cells)
                    .map_err(|e| e.to_string())?;
                let mut col_idx = 0u16;
                while cell_iter.next() && col_idx < out_width {
                    if cell_iter.is_default_blank().unwrap_or(false) {
                        cells.push(CellData::default());
                        col_idx += 1;
                        continue;
                    }
                    let basic = cell_iter.basic_data().map_err(|e| e.to_string())?;
                    if matches!(basic.wide, CellWide::SpacerTail | CellWide::SpacerHead) {
                        cells.push(CellData {
                            symbol: String::new(),
                            fg: convert_cell_color(basic.style.fg_color),
                            bg: convert_cell_color(
                                basic
                                    .style
                                    .bg_color
                                    .or_else(|| cell_iter.content_bg_color().ok().flatten()),
                            ),
                            modifier: convert_style_modifiers(&basic.style),
                            skip: true,
                            hyperlink: None,
                        });
                        col_idx += 1;
                        continue;
                    }

                    cell_iter
                        .grapheme_text_into(&mut scratch_bytes, &mut scratch_text)
                        .map_err(|e| e.to_string())?;
                    let symbol = if scratch_text.is_empty() {
                        " ".to_owned()
                    } else {
                        scratch_text.clone()
                    };
                    let fg = convert_cell_color(basic.style.fg_color);
                    let bg = convert_cell_color(
                        basic
                            .style
                            .bg_color
                            .or_else(|| cell_iter.content_bg_color().ok().flatten()),
                    );
                    let modifier = convert_style_modifiers(&basic.style);

                    cells.push(CellData {
                        symbol,
                        fg,
                        bg,
                        modifier,
                        skip: false,
                        hyperlink: None,
                    });
                    col_idx += 1;
                }
                while col_idx < out_width {
                    cells.push(CellData::default());
                    col_idx += 1;
                }
            }
        }

        while cells.len() < total_cells {
            cells.push(CellData::default());
        }

        // Extract cursor from libghostty-vt, adjusting row by -1 when top tab bar row is stripped.
        let render_cursor = self.render_state.cursor().map_err(|e| e.to_string())?;
        let mut cursor = render_cursor.viewport.and_then(|vp| {
            let adjusted_y = if strip_top_row {
                vp.y.checked_sub(1)?
            } else {
                vp.y
            };
            (vp.x < out_width && adjusted_y < out_height).then_some(CursorState {
                x: vp.x,
                y: adjusted_y,
                visible: render_cursor.visible,
                shape: cursor_shape_param(render_cursor.visual_style, render_cursor.blinking),
            })
        });

        // Overlay Mosh client-side predictive echo cells (underlined until acked).
        if let Some(echo) = predictive_echo {
            if echo.is_enabled() {
                for predicted in echo.pending_cells() {
                    if predicted.x < out_width && predicted.y < out_height {
                        let idx = usize::from(predicted.y) * usize::from(out_width)
                            + usize::from(predicted.x);
                        if let Some(cell) = cells.get_mut(idx) {
                            cell.symbol.clone_from(&predicted.symbol);
                            cell.modifier |= MODIFIER_UNDERLINED;
                            cell.skip = false;
                        }
                    }
                }
                if let Some((px, py)) = echo.predicted_cursor() {
                    if px < out_width && py < out_height {
                        cursor = Some(CursorState {
                            x: px,
                            y: py,
                            visible: true,
                            shape: cursor.as_ref().map_or(2, |c| c.shape),
                        });
                    }
                }
            }
        }

        let alternate_screen_active = matches!(
            self.terminal.active_screen(),
            Ok(ActiveScreen::Alternate)
        );
        let mouse_reporting = self.terminal.mouse_tracking_enabled().unwrap_or(false);

        let (panes, splits) = if let Some(snapshot) = layout {
            if snapshot.zoomed {
                let focused_id = if !snapshot.focused_pane_id.is_empty() {
                    snapshot.focused_pane_id.clone()
                } else {
                    snapshot
                        .panes
                        .iter()
                        .find(|p| p.focused)
                        .or_else(|| snapshot.panes.first())
                        .map(|p| p.pane_id.clone())
                        .unwrap_or_else(|| "w1:p1".into())
                };
                let full_rect = SurfaceRect {
                    x: 0,
                    y: 0,
                    width: out_width,
                    height: out_height,
                };
                (
                    vec![PaneSurfacePane {
                        pane_id: focused_id,
                        content_revision: surface_revision,
                        rect: full_rect,
                        inner_rect: full_rect,
                        scrollbar_rect: None,
                        scroll: None,
                        focused: true,
                        mouse_reporting,
                        sgr_pixel_mouse: false,
                        alternate_screen_active,
                        pixel_width: 0,
                        pixel_height: 0,
                    }],
                    Vec::new(),
                )
            } else {
                let panes = snapshot
                    .panes
                    .iter()
                    .map(|p| {
                        let rect = SurfaceRect::from(p.rect);
                        PaneSurfacePane {
                            pane_id: p.pane_id.clone(),
                            content_revision: surface_revision,
                            rect,
                            inner_rect: rect,
                            scrollbar_rect: None,
                            scroll: None,
                            focused: p.focused || p.pane_id == snapshot.focused_pane_id,
                            mouse_reporting,
                            sgr_pixel_mouse: false,
                            alternate_screen_active,
                            pixel_width: 0,
                            pixel_height: 0,
                        }
                    })
                    .collect();
                let splits = derive_splits_from_layout(snapshot);
                (panes, splits)
            }
        } else {
            let full_rect = SurfaceRect {
                x: 0,
                y: 0,
                width: out_width,
                height: out_height,
            };
            (
                vec![PaneSurfacePane {
                    pane_id: "w1:p1".into(),
                    content_revision: surface_revision,
                    rect: full_rect,
                    inner_rect: full_rect,
                    scrollbar_rect: None,
                    scroll: None,
                    focused: true,
                    mouse_reporting,
                    sgr_pixel_mouse: false,
                    alternate_screen_active,
                    pixel_width: 0,
                    pixel_height: 0,
                }],
                Vec::new(),
            )
        };

        Ok(PaneSurfaceFrame {
            boot_id: boot_id.to_owned(),
            projection_revision,
            surface_revision,
            frame: FrameData {
                cells,
                width: out_width,
                height: out_height,
                cursor,
                hyperlinks: Vec::new(),
                graphics: Vec::new(),
            },
            panes,
            splits,
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use herdr_protocol::{
        PaneLayoutPane, PaneLayoutRect, PaneLayoutSplit, PaneLayoutSplitDirection,
        PaneSurfaceSplitDirection,
    };

    #[test]
    fn synthesizes_vt_cells_and_strips_top_tab_row_when_multi_tab() {
        // 2 tabs -> PTY is allocated with 5 + 1 = 6 rows, row 0 is stripped
        let mut synth = VtSynthesizer::new(20, 5, 2, 100).unwrap();
        assert_eq!(synth.pty_rows(), 6);
        assert_eq!(synth.viewport_rows(), 5);

        // Write tab bar on row 1 (1-based) and pane content on row 2 (1-based)
        synth.feed_vt_bytes(b"\x1b[1;1HTAB1 | TAB2\x1b[2;1H\x1b[1;32mHello Mosh!\x1b[0m");

        let layout = PaneLayoutSnapshot {
            workspace_id: "w1".into(),
            tab_id: "w1:t1".into(),
            zoomed: false,
            area: PaneLayoutRect {
                x: 0,
                y: 0,
                width: 20,
                height: 5,
            },
            focused_pane_id: "w1:p1".into(),
            panes: vec![
                PaneLayoutPane {
                    pane_id: "w1:p1".into(),
                    focused: true,
                    rect: PaneLayoutRect {
                        x: 0,
                        y: 0,
                        width: 10,
                        height: 5,
                    },
                },
                PaneLayoutPane {
                    pane_id: "w1:p2".into(),
                    focused: false,
                    rect: PaneLayoutRect {
                        x: 10,
                        y: 0,
                        width: 10,
                        height: 5,
                    },
                },
            ],
            splits: vec![PaneLayoutSplit {
                id: "s0:root".into(),
                direction: PaneLayoutSplitDirection::Right,
                ratio: 0.5,
                rect: PaneLayoutRect {
                    x: 0,
                    y: 0,
                    width: 20,
                    height: 5,
                },
            }],
        };

        let mut echo = PredictiveEchoEngine::new(true, 20);
        echo.sync_cursor(11, 0, 20);
        echo.predict_keystroke(1, '!');

        let surface = synth
            .synthesize_frame("boot-mosh", 1, 1, Some(&layout), Some(&echo))
            .unwrap();

        assert_eq!(surface.frame.width, 20);
        assert_eq!(surface.frame.height, 5);
        // Row 0 of synthesized frame must be "Hello Mosh!!" (row 1 of PTY stripped!)
        let row0: String = surface.frame.cells[0..12]
            .iter()
            .map(|c| c.symbol.as_str())
            .collect();
        assert_eq!(row0, "Hello Mosh!!");
        // Bold modifier on 'H'
        assert_ne!(surface.frame.cells[0].modifier & MODIFIER_BOLD, 0);
        // Underlined modifier on predicted '!' at col 11
        assert_ne!(surface.frame.cells[11].modifier & MODIFIER_UNDERLINED, 0);
        // Split derived from layout
        assert_eq!(surface.splits.len(), 1);
        assert_eq!(
            surface.splits[0].direction,
            PaneSurfaceSplitDirection::Horizontal
        );
        assert_eq!(surface.splits[0].pos, 10);
    }
}
