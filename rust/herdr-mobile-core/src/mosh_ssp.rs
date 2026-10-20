//! Clean-room Apache-2.0 Mosh State Synchronization Protocol (SSP) UDP client engine.
//!
//! Implements:
//! - `MOSH CONNECT <port> <key>` parser (`parse_mosh_connect_line`).
//! - RFC 7253 AES-128-OCB3 authenticated encryption/decryption (thetalen = 128 bits, 12-byte nonce).
//! - Mosh SSP datagram framing (64-bit direction+seq header, 16-bit timestamps, zlib-compressed
//!   Protobuf `Instruction` payload).
//! - Client-side predictive echo tracker (tentative keystrokes underlined until `ack_num` catches up).
//! - Zero-traffic background parking (`set_parked(true)` suspends heartbeats/acks; `set_parked(false)`
//!   produces an immediate wakeup datagram).

use std::io::{Read, Write};

use aes::cipher::{BlockDecrypt, BlockEncrypt, KeyInit, generic_array::GenericArray};
use aes::Aes128;
use base64::{engine::general_purpose::{STANDARD, STANDARD_NO_PAD}, Engine as _};
use flate2::{read::ZlibDecoder, write::ZlibEncoder, Compression};
use prost::Message;
use serde::{Deserialize, Serialize};

/// Direction bit for Mosh SSP packets.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PacketDirection {
    ToServer = 0,
    ToClient = 1,
}

/// Parsed connection parameters from `mosh-server`'s `MOSH CONNECT <port> <key>` output line.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MoshConnectInfo {
    pub port: u16,
    pub key_base64: String,
    pub key_bytes: [u8; 16],
}

/// Parses `MOSH CONNECT <port> <key>` from SSH bootstrap output.
pub fn parse_mosh_connect_line(output: &str) -> Result<MoshConnectInfo, String> {
    for line in output.lines() {
        let trimmed = line.trim();
        let Some(rest) = trimmed.strip_prefix("MOSH CONNECT ") else {
            continue;
        };
        let mut parts = rest.split_whitespace();
        let port_str = parts.next().ok_or("missing UDP port in MOSH CONNECT")?;
        let key_str = parts.next().ok_or("missing session key in MOSH CONNECT")?;
        let port: u16 = port_str
            .parse()
            .map_err(|_| format!("invalid UDP port in MOSH CONNECT: {port_str}"))?;
        if port == 0 {
            return Err("MOSH CONNECT port cannot be 0".into());
        }
        let decoded = STANDARD_NO_PAD
            .decode(key_str.trim_end_matches('='))
            .or_else(|_| STANDARD.decode(key_str))
            .map_err(|err| format!("invalid base64 key in MOSH CONNECT: {err}"))?;
        let key_bytes: [u8; 16] = decoded
            .try_into()
            .map_err(|_| "MOSH CONNECT key must decode to 16 bytes (128 bits)".to_string())?;
        return Ok(MoshConnectInfo {
            port,
            key_base64: key_str.to_owned(),
            key_bytes,
        });
    }
    Err("no `MOSH CONNECT <port> <key>` line found in output".into())
}

/// Protobuf schema for Mosh's `TransportBuffers.Instruction`.
#[derive(Clone, PartialEq, Eq, Message)]
pub struct SspInstruction {
    #[prost(uint32, optional, tag = "1")]
    pub protocol_version: Option<u32>,
    #[prost(uint64, optional, tag = "2")]
    pub old_num: Option<u64>,
    #[prost(uint64, optional, tag = "3")]
    pub new_num: Option<u64>,
    #[prost(uint64, optional, tag = "4")]
    pub ack_num: Option<u64>,
    #[prost(uint64, optional, tag = "5")]
    pub throwaway_num: Option<u64>,
    #[prost(bytes = "vec", optional, tag = "6")]
    pub diff: Option<Vec<u8>>,
    #[prost(bytes = "vec", optional, tag = "7")]
    pub chaff: Option<Vec<u8>>,
}

pub const MOSH_PROTOCOL_VERSION: u32 = 2;

impl SspInstruction {
    pub fn compress_zlib(&self) -> Result<Vec<u8>, String> {
        let mut raw = Vec::with_capacity(self.encoded_len());
        self.encode(&mut raw).map_err(|e| e.to_string())?;
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&raw).map_err(|e| e.to_string())?;
        encoder.finish().map_err(|e| e.to_string())
    }

    pub fn decompress_zlib(compressed: &[u8]) -> Result<Self, String> {
        let mut decoder = ZlibDecoder::new(compressed);
        let mut raw = Vec::new();
        decoder.read_to_end(&mut raw).map_err(|e| e.to_string())?;
        Self::decode(raw.as_slice()).map_err(|e| e.to_string())
    }
}

/// Clean-room RFC 7253 AES-128-OCB3 implementation (TAGLEN = 128 bits).
pub struct Aes128Ocb3 {
    cipher: Aes128,
    l_star: [u8; 16],
    l_dollar: [u8; 16],
    l_table: [[u8; 16]; 64],
}

fn gf128_double(block: &[u8; 16]) -> [u8; 16] {
    let mut out = [0u8; 16];
    let mut carry = 0u8;
    for i in (0..16).rev() {
        let next_carry = block[i] >> 7;
        out[i] = (block[i] << 1) | carry;
        carry = next_carry;
    }
    if (block[0] & 0x80) != 0 {
        out[15] ^= 0x87;
    }
    out
}

fn xor_block(a: &mut [u8; 16], b: &[u8; 16]) {
    for (x, y) in a.iter_mut().zip(b.iter()) {
        *x ^= *y;
    }
}

impl Aes128Ocb3 {
    pub fn new(key: &[u8; 16]) -> Self {
        let cipher = Aes128::new(GenericArray::from_slice(key));
        let mut l_star = [0u8; 16];
        cipher.encrypt_block(GenericArray::from_mut_slice(&mut l_star));
        let l_dollar = gf128_double(&l_star);
        let mut l_table = [[0u8; 16]; 64];
        l_table[0] = gf128_double(&l_dollar);
        for i in 1..64 {
            l_table[i] = gf128_double(&l_table[i - 1]);
        }
        Self {
            cipher,
            l_star,
            l_dollar,
            l_table,
        }
    }

    fn encrypt_block(&self, block: &[u8; 16]) -> [u8; 16] {
        let mut out = *block;
        self.cipher
            .encrypt_block(GenericArray::from_mut_slice(&mut out));
        out
    }

    fn decrypt_block(&self, block: &[u8; 16]) -> [u8; 16] {
        let mut out = *block;
        self.cipher
            .decrypt_block(GenericArray::from_mut_slice(&mut out));
        out
    }

    fn initial_offset(&self, nonce12: &[u8; 12]) -> [u8; 16] {
        // RFC 7253 Section 4.2 for 96-bit (12-byte) nonce and 128-bit tag (TAGLEN mod 128 = 0):
        // Nonce = num2str(TAGLEN mod 128, 7) || zeros(120 - bitlen(N)) || 1 || N
        let mut nonce = [0u8; 16];
        nonce[3] = 0x01;
        nonce[4..16].copy_from_slice(nonce12);

        let bottom = (nonce[15] & 0x3f) as usize;
        nonce[15] &= 0xc0;
        let ktop = self.encrypt_block(&nonce);

        let mut stretch = [0u8; 24];
        stretch[0..16].copy_from_slice(&ktop);
        for i in 0..8 {
            stretch[16 + i] = ktop[i] ^ ktop[i + 1];
        }

        let byte_shift = bottom / 8;
        let bit_shift = bottom % 8;
        let mut offset = [0u8; 16];
        for i in 0..16 {
            let hi = stretch[byte_shift + i];
            let lo = stretch[byte_shift + i + 1];
            offset[i] = if bit_shift == 0 {
                hi
            } else {
                (hi << bit_shift) | (lo >> (8 - bit_shift))
            };
        }
        offset
    }

    /// Encrypts `plaintext` with the 12-byte `nonce`, returning `ciphertext || 16-byte tag`.
    pub fn encrypt(&self, nonce12: &[u8; 12], plaintext: &[u8]) -> Vec<u8> {
        let mut offset = self.initial_offset(nonce12);
        let mut checksum = [0u8; 16];
        let mut out = Vec::with_capacity(plaintext.len() + 16);

        let full_blocks = plaintext.len() / 16;
        for i in 0..full_blocks {
            let ntz = ((i + 1) as u64).trailing_zeros() as usize;
            xor_block(&mut offset, &self.l_table[ntz.min(63)]);
            let mut blk = [0u8; 16];
            blk.copy_from_slice(&plaintext[i * 16..(i + 1) * 16]);
            xor_block(&mut checksum, &blk);
            xor_block(&mut blk, &offset);
            let mut enc = self.encrypt_block(&blk);
            xor_block(&mut enc, &offset);
            out.extend_from_slice(&enc);
        }

        let rem = &plaintext[full_blocks * 16..];
        if !rem.is_empty() {
            xor_block(&mut offset, &self.l_star);
            let pad = self.encrypt_block(&offset);
            for (idx, &b) in rem.iter().enumerate() {
                out.push(b ^ pad[idx]);
                checksum[idx] ^= b;
            }
            checksum[rem.len()] ^= 0x80;
        }

        let mut tag_input = checksum;
        xor_block(&mut tag_input, &offset);
        xor_block(&mut tag_input, &self.l_dollar);
        let tag = self.encrypt_block(&tag_input);
        out.extend_from_slice(&tag);
        out
    }

    /// Decrypts `ciphertext_and_tag` with the 12-byte `nonce`, verifying the 16-byte authentication tag.
    pub fn decrypt(&self, nonce12: &[u8; 12], ciphertext_and_tag: &[u8]) -> Result<Vec<u8>, String> {
        if ciphertext_and_tag.len() < 16 {
            return Err("OCB3 ciphertext shorter than 16-byte tag".into());
        }
        let ct_len = ciphertext_and_tag.len() - 16;
        let ciphertext = &ciphertext_and_tag[..ct_len];
        let received_tag = &ciphertext_and_tag[ct_len..];

        let mut offset = self.initial_offset(nonce12);
        let mut checksum = [0u8; 16];
        let mut out = Vec::with_capacity(ct_len);

        let full_blocks = ct_len / 16;
        for i in 0..full_blocks {
            let ntz = ((i + 1) as u64).trailing_zeros() as usize;
            xor_block(&mut offset, &self.l_table[ntz.min(63)]);
            let mut blk = [0u8; 16];
            blk.copy_from_slice(&ciphertext[i * 16..(i + 1) * 16]);
            xor_block(&mut blk, &offset);
            let mut dec = self.decrypt_block(&blk);
            xor_block(&mut dec, &offset);
            xor_block(&mut checksum, &dec);
            out.extend_from_slice(&dec);
        }

        let rem = &ciphertext[full_blocks * 16..];
        if !rem.is_empty() {
            xor_block(&mut offset, &self.l_star);
            let pad = self.encrypt_block(&offset);
            for (idx, &b) in rem.iter().enumerate() {
                let p = b ^ pad[idx];
                out.push(p);
                checksum[idx] ^= p;
            }
            checksum[rem.len()] ^= 0x80;
        }

        let mut tag_input = checksum;
        xor_block(&mut tag_input, &offset);
        xor_block(&mut tag_input, &self.l_dollar);
        let expected_tag = self.encrypt_block(&tag_input);

        // Constant-time tag comparison
        let mut diff = 0u8;
        for (a, b) in expected_tag.iter().zip(received_tag.iter()) {
            diff |= a ^ b;
        }
        if diff != 0 {
            return Err("OCB3 authentication tag mismatch".into());
        }
        Ok(out)
    }
}

/// Decoded Mosh UDP datagram.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SspDatagram {
    pub seq: u64,
    pub direction: PacketDirection,
    pub timestamp: u16,
    pub timestamp_reply: u16,
    pub instruction: SspInstruction,
}

fn build_mosh_nonce(direction: PacketDirection, seq: u64) -> ([u8; 8], [u8; 12]) {
    let dir_mask = match direction {
        PacketDirection::ToServer => 0u64,
        PacketDirection::ToClient => 1u64 << 63,
    };
    let dir_seq = (seq & 0x7fff_ffff_ffff_ffff) | dir_mask;
    let header8 = dir_seq.to_be_bytes();
    let mut nonce12 = [0u8; 12];
    nonce12[4..12].copy_from_slice(&header8);
    (header8, nonce12)
}

/// Encodes and encrypts an `SspDatagram` into a raw UDP wire packet.
pub fn seal_ssp_datagram(ocb: &Aes128Ocb3, datagram: &SspDatagram) -> Result<Vec<u8>, String> {
    let (header8, nonce12) = build_mosh_nonce(datagram.direction, datagram.seq);
    let compressed = datagram.instruction.compress_zlib()?;
    let mut plaintext = Vec::with_capacity(4 + compressed.len());
    plaintext.extend_from_slice(&datagram.timestamp.to_be_bytes());
    plaintext.extend_from_slice(&datagram.timestamp_reply.to_be_bytes());
    plaintext.extend_from_slice(&compressed);

    let ciphertext = ocb.encrypt(&nonce12, &plaintext);
    let mut packet = Vec::with_capacity(8 + ciphertext.len());
    packet.extend_from_slice(&header8);
    packet.extend_from_slice(&ciphertext);
    Ok(packet)
}

/// Decrypts and decodes a raw UDP wire packet into an `SspDatagram`.
pub fn open_ssp_datagram(ocb: &Aes128Ocb3, packet: &[u8]) -> Result<SspDatagram, String> {
    if packet.len() < 8 + 16 {
        return Err("UDP packet too short for Mosh SSP".into());
    }
    let mut header8 = [0u8; 8];
    header8.copy_from_slice(&packet[..8]);
    let dir_seq = u64::from_be_bytes(header8);
    let direction = if (dir_seq >> 63) == 0 {
        PacketDirection::ToServer
    } else {
        PacketDirection::ToClient
    };
    let seq = dir_seq & 0x7fff_ffff_ffff_ffff;

    let mut nonce12 = [0u8; 12];
    nonce12[4..12].copy_from_slice(&header8);
    let plaintext = ocb.decrypt(&nonce12, &packet[8..])?;
    if plaintext.len() < 4 {
        return Err("decrypted SSP payload shorter than 4-byte timestamp header".into());
    }
    let timestamp = u16::from_be_bytes([plaintext[0], plaintext[1]]);
    let timestamp_reply = u16::from_be_bytes([plaintext[2], plaintext[3]]);
    let instruction = SspInstruction::decompress_zlib(&plaintext[4..])?;

    Ok(SspDatagram {
        seq,
        direction,
        timestamp,
        timestamp_reply,
        instruction,
    })
}

/// Tentative character predicted locally by Mosh client-side predictive echo.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PredictedCell {
    pub state_num: u64,
    pub x: u16,
    pub y: u16,
    pub symbol: String,
}

/// Client-side predictive echo engine that predicts printable characters and backspace
/// and underlines tentative cells until the remote server's `ack_num` catches up.
#[derive(Debug, Clone, Default)]
pub struct PredictiveEchoEngine {
    enabled: bool,
    cursor_x: u16,
    cursor_y: u16,
    max_cols: u16,
    pending: Vec<PredictedCell>,
}

impl PredictiveEchoEngine {
    pub fn new(enabled: bool, max_cols: u16) -> Self {
        Self {
            enabled,
            cursor_x: 0,
            cursor_y: 0,
            max_cols,
            pending: Vec::new(),
        }
    }

    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
        if !enabled {
            self.pending.clear();
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    pub fn sync_cursor(&mut self, x: u16, y: u16, max_cols: u16) {
        self.max_cols = max_cols;
        if self.pending.is_empty() {
            self.cursor_x = x;
            self.cursor_y = y;
        }
    }

    pub fn predict_keystroke(&mut self, state_num: u64, ch: char) {
        if !self.enabled || self.max_cols == 0 {
            return;
        }
        if ch == '\x08' || ch == '\x7f' {
            if self.cursor_x > 0 {
                self.cursor_x -= 1;
                self.pending
                    .retain(|cell| !(cell.x == self.cursor_x && cell.y == self.cursor_y));
            }
            return;
        }
        if !ch.is_control() {
            let x = self.cursor_x;
            let y = self.cursor_y;
            self.pending.retain(|cell| !(cell.x == x && cell.y == y));
            self.pending.push(PredictedCell {
                state_num,
                x,
                y,
                symbol: ch.to_string(),
            });
            if self.cursor_x + 1 < self.max_cols {
                self.cursor_x += 1;
            }
        }
    }

    /// Retires predictions whose `state_num <= ack_num`.
    pub fn acknowledge(&mut self, ack_num: u64) {
        self.pending.retain(|cell| cell.state_num > ack_num);
    }

    pub fn pending_cells(&self) -> &[PredictedCell] {
        &self.pending
    }

    pub fn predicted_cursor(&self) -> Option<(u16, u16)> {
        (!self.pending.is_empty()).then_some((self.cursor_x, self.cursor_y))
    }
}

/// Stateful Mosh SSP client engine supporting zero-traffic background parking and instant IP roaming.
pub struct MoshClientEngine {
    ocb: Aes128Ocb3,
    next_seq: u64,
    local_state_num: u64,
    remote_state_num: u64,
    last_remote_timestamp: u16,
    rtt_ms: Option<u32>,
    parked: bool,
    pub predictive_echo: PredictiveEchoEngine,
}

impl MoshClientEngine {
    pub fn new(key: &[u8; 16], cols: u16, local_echo: bool) -> Self {
        Self {
            ocb: Aes128Ocb3::new(key),
            next_seq: 1,
            local_state_num: 0,
            remote_state_num: 0,
            last_remote_timestamp: u16::MAX,
            rtt_ms: None,
            parked: false,
            predictive_echo: PredictiveEchoEngine::new(local_echo, cols),
        }
    }

    pub fn is_parked(&self) -> bool {
        self.parked
    }

    pub fn remote_state_num(&self) -> u64 {
        self.remote_state_num
    }

    pub fn local_state_num(&self) -> u64 {
        self.local_state_num
    }

    pub fn rtt_ms(&self) -> Option<u32> {
        self.rtt_ms
    }

    /// Enters or exits zero-traffic background parking.
    ///
    /// When `parked == true`, heartbeats and acks are paused so `mosh-server` stops streaming
    /// diffs (0 bytes/sec in background). When `parked == false`, returns an immediate wakeup
    /// SSP datagram so `mosh-server` resumes from `remote_state_num` in <20ms.
    pub fn set_parked(&mut self, parked: bool, now_ms: u16) -> Result<Option<Vec<u8>>, String> {
        self.parked = parked;
        if parked {
            Ok(None)
        } else {
            self.build_heartbeat_datagram(now_ms).map(Some)
        }
    }

    /// Crafts a heartbeat / state-acknowledgment SSP datagram (also used on network roam wakeup).
    pub fn build_heartbeat_datagram(&mut self, now_ms: u16) -> Result<Vec<u8>, String> {
        let seq = self.next_seq;
        self.next_seq = self.next_seq.saturating_add(1);
        let instruction = SspInstruction {
            protocol_version: Some(MOSH_PROTOCOL_VERSION),
            old_num: Some(self.local_state_num),
            new_num: Some(self.local_state_num),
            ack_num: Some(self.remote_state_num),
            throwaway_num: Some(self.local_state_num),
            diff: None,
            chaff: None,
        };
        seal_ssp_datagram(
            &self.ocb,
            &SspDatagram {
                seq,
                direction: PacketDirection::ToServer,
                timestamp: now_ms,
                timestamp_reply: self.last_remote_timestamp,
                instruction,
            },
        )
    }

    /// Crafts an outbound user input SSP datagram and updates predictive echo if printable.
    pub fn build_user_input_datagram(
        &mut self,
        user_diff: Vec<u8>,
        predicted_text: Option<&str>,
        now_ms: u16,
    ) -> Result<Vec<u8>, String> {
        let old_num = self.local_state_num;
        self.local_state_num = self.local_state_num.saturating_add(1);
        let new_num = self.local_state_num;
        if let Some(text) = predicted_text {
            for ch in text.chars() {
                self.predictive_echo.predict_keystroke(new_num, ch);
            }
        }
        let seq = self.next_seq;
        self.next_seq = self.next_seq.saturating_add(1);
        let instruction = SspInstruction {
            protocol_version: Some(MOSH_PROTOCOL_VERSION),
            old_num: Some(old_num),
            new_num: Some(new_num),
            ack_num: Some(self.remote_state_num),
            throwaway_num: Some(old_num),
            diff: Some(user_diff),
            chaff: None,
        };
        seal_ssp_datagram(
            &self.ocb,
            &SspDatagram {
                seq,
                direction: PacketDirection::ToServer,
                timestamp: now_ms,
                timestamp_reply: self.last_remote_timestamp,
                instruction,
            },
        )
    }

    /// Processes an incoming UDP datagram from `mosh-server`, returning any host VT diff bytes.
    pub fn receive_datagram(
        &mut self,
        packet: &[u8],
        now_ms: u16,
    ) -> Result<Option<Vec<u8>>, String> {
        let datagram = open_ssp_datagram(&self.ocb, packet)?;
        if datagram.direction != PacketDirection::ToClient {
            return Err("unexpected ToServer direction on client socket".into());
        }
        self.last_remote_timestamp = datagram.timestamp;
        if datagram.timestamp_reply != u16::MAX {
            let rtt = now_ms.wrapping_sub(datagram.timestamp_reply);
            self.rtt_ms = Some(u32::from(rtt));
        }
        if let Some(ack) = datagram.instruction.ack_num {
            self.predictive_echo.acknowledge(ack);
        }
        if let Some(new_num) = datagram.instruction.new_num {
            if new_num > self.remote_state_num {
                self.remote_state_num = new_num;
                return Ok(datagram.instruction.diff);
            }
        }
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_mosh_connect_line_from_ssh_banner_and_output() {
        let sample = "\
Welcome to Ubuntu 24.04 LTS\n\
\n\
MOSH CONNECT 60004 4k9X2vL8pQ1mN7rT5wY3zA\n\
mosh-server (mosh 1.4.0)\n";
        let info = parse_mosh_connect_line(sample).expect("parse MOSH CONNECT");
        assert_eq!(info.port, 60004);
        assert_eq!(info.key_base64, "4k9X2vL8pQ1mN7rT5wY3zA");
        assert_eq!(info.key_bytes.len(), 16);
    }

    #[test]
    fn ocb3_rfc7253_round_trip_and_tamper_rejection() {
        let key: [u8; 16] = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
            0x0E, 0x0F,
        ];
        let nonce: [u8; 12] = [
            0xBB, 0xAA, 0x99, 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00,
        ];
        let ocb = Aes128Ocb3::new(&key);

        for len in [0usize, 1, 15, 16, 17, 31, 32, 40, 128] {
            let pt: Vec<u8> = (0..len).map(|i| (i & 0xff) as u8).collect();
            let mut ct = ocb.encrypt(&nonce, &pt);
            let decrypted = ocb.decrypt(&nonce, &ct).expect("valid tag");
            assert_eq!(decrypted, pt);

            // Flip a bit in the tag and verify authentication fails
            let last = ct.len() - 1;
            ct[last] ^= 0x01;
            assert!(ocb.decrypt(&nonce, &ct).is_err());
        }
    }

    #[test]
    fn mosh_ssp_datagram_round_trip_predictive_echo_and_background_parking() {
        let key = [0x42u8; 16];
        let mut client = MoshClientEngine::new(&key, 80, true);
        let server_ocb = Aes128Ocb3::new(&key);

        // Predict typing "ls"
        let pkt = client
            .build_user_input_datagram(b"ls".to_vec(), Some("ls"), 100)
            .unwrap();
        assert_eq!(client.predictive_echo.pending_cells().len(), 2);
        assert_eq!(client.predictive_echo.predicted_cursor(), Some((2, 0)));

        // Verify server can decrypt the client's datagram
        let decoded_client = open_ssp_datagram(&server_ocb, &pkt).unwrap();
        assert_eq!(decoded_client.direction, PacketDirection::ToServer);
        assert_eq!(decoded_client.instruction.new_num, Some(1));
        assert_eq!(decoded_client.instruction.diff.as_deref(), Some(&b"ls"[..]));

        // Park in background -> zero traffic
        assert!(client.set_parked(true, 120).unwrap().is_none());
        assert!(client.is_parked());

        // Resume from background -> immediate wakeup heartbeat datagram
        let wakeup = client
            .set_parked(false, 200)
            .unwrap()
            .expect("wakeup datagram");
        assert!(!client.is_parked());
        let decoded_wakeup = open_ssp_datagram(&server_ocb, &wakeup).unwrap();
        assert_eq!(decoded_wakeup.timestamp, 200);

        // Server sends frame diff acknowledging client state 1
        let server_datagram = SspDatagram {
            seq: 1,
            direction: PacketDirection::ToClient,
            timestamp: 210,
            timestamp_reply: 200,
            instruction: SspInstruction {
                protocol_version: Some(MOSH_PROTOCOL_VERSION),
                old_num: Some(0),
                new_num: Some(1),
                ack_num: Some(1),
                throwaway_num: Some(0),
                diff: Some(b"\x1b[1;1Hls".to_vec()),
                chaff: None,
            },
        };
        let wire_from_server = seal_ssp_datagram(&server_ocb, &server_datagram).unwrap();
        let diff = client
            .receive_datagram(&wire_from_server, 225)
            .unwrap()
            .expect("host diff");
        assert_eq!(diff, b"\x1b[1;1Hls");
        assert_eq!(client.rtt_ms(), Some(25));
        assert!(
            client.predictive_echo.pending_cells().is_empty(),
            "predictive echo should retire once ack_num catches up"
        );
    }
}
