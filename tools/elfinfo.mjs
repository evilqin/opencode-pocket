// 轻量 ELF 检查器：列出 PT_INTERP / DT_NEEDED / DT_SONAME / 机器类型
// 用法: node tools/elfinfo.mjs <file> [...]
import fs from "node:fs";

const DT_NEEDED = 1, DT_SONAME = 14;

function parse(buf) {
  if (buf.readUInt32BE(0) !== 0x7f454c46) throw new Error("not ELF");
  const cls = buf[4]; // 2 = 64bit
  const little = buf[5] === 1;
  const r16 = (o) => (little ? buf.readUInt16LE(o) : buf.readUInt16BE(o));
  const r32 = (o) => (little ? buf.readUInt32LE(o) : buf.readUInt32BE(o));
  const r64 = (o) => (little ? buf.readBigUInt64LE(o) : buf.readBigUInt64BE(o));

  const eMachine = r16(18);
  const eType = r16(16);
  let phoff, shoff, phentsize, phnum, shentsize, shnum, shstrndx;
  if (cls === 2) {
    phoff = Number(r64(32)); shoff = Number(r64(40));
    phentsize = r16(54); phnum = r16(56); shentsize = r16(58); shnum = r16(60); shstrndx = r16(62);
  } else {
    phoff = r32(28); shoff = r32(32);
    phentsize = r16(42); phnum = r16(44); shentsize = r16(46); shnum = r16(48); shstrndx = r16(50);
  }

  const out = { cls, little, eMachine, eType, interp: null, needed: [], soname: null, sections: [], dynamic: [] };

  // program headers
  for (let i = 0; i < phnum; i++) {
    const off = phoff + i * phentsize;
    const type = r32(off);
    if (type === 3) { // PT_INTERP
      let pOff, pFilesz;
      if (cls === 2) { pOff = Number(r64(off + 8)); pFilesz = Number(r64(off + 32)); }
      else { pOff = r32(off + 4); pFilesz = r32(off + 16); }
      out.interp = buf.subarray(pOff, pOff + pFilesz - 1).toString("utf8");
    }
  }

  // section headers
  const sections = [];
  for (let i = 0; i < shnum; i++) {
    const off = shoff + i * shentsize;
    let name, type, addr, offset, size, link, entsize;
    if (cls === 2) {
      name = r32(off); type = r32(off + 4);
      addr = Number(r64(off + 16)); offset = Number(r64(off + 24)); size = Number(r64(off + 32));
      link = r32(off + 40); entsize = Number(r64(off + 56));
    } else {
      name = r32(off); type = r32(off + 4);
      addr = r32(off + 12); offset = r32(off + 16); size = r32(off + 20);
      link = r32(off + 24); entsize = r32(off + 36);
    }
    sections.push({ name, type, addr, offset, size, link, entsize });
  }
  // section names
  if (shstrndx < sections.length) {
    const strtab = sections[shstrndx];
    for (const s of sections) {
      const start = strtab.offset + s.name;
      let end = start;
      while (buf[end] !== 0) end++;
      s.sname = buf.subarray(start, end).toString("utf8");
    }
  }

  const dynamic = sections.find((s) => s.type === 6); // SHT_DYNAMIC
  if (dynamic) {
    const dynstr = sections[dynamic.link];
    const step = cls === 2 ? 16 : 8;
    for (let o = dynamic.offset; o < dynamic.offset + dynamic.size; o += step) {
      const tag = cls === 2 ? Number(r64(o)) : r32(o);
      const val = cls === 2 ? Number(r64(o + 8)) : r32(o + 4);
      if (tag === 0) break;
      if (tag === DT_NEEDED || tag === DT_SONAME) {
        const start = dynstr.offset + val;
        let end = start;
        while (buf[end] !== 0) end++;
        const str = buf.subarray(start, end).toString("utf8");
        if (tag === DT_NEEDED) out.needed.push(str); else out.soname = str;
      }
      out.dynamic.push({ tag, val });
    }
  }
  out.sections = sections.map((s) => s.sname).filter(Boolean);
  return out;
}

for (const file of process.argv.slice(2)) {
  try {
    const buf = fs.readFileSync(file);
    const info = parse(buf);
    const machines = { 0x28: "arm(32)", 0xb7: "aarch64", 0x3e: "x86_64", 0x03: "i386" };
    console.log(`== ${file}`);
    console.log(`   size=${buf.length} type=${info.eType === 3 ? "DYN(PIE)" : info.eType === 2 ? "EXEC" : info.eType} mach=${machines[info.eMachine] || info.eMachine} interp=${info.interp ?? "(none/static)"}`);
    console.log(`   needed=[${info.needed.join(", ")}] soname=${info.soname ?? "-"}`);
    const sect = info.sections.filter((s) => s && s.length && (s.startsWith(".text") || s.startsWith(".note") || s.startsWith(".dyn")));
    console.log(`   sections: ${sect.slice(0, 6).join(" ")}`);
  } catch (e) {
    console.log(`== ${file}: ERROR ${e.message}`);
  }
}
