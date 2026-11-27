import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [, , simplifierJsPath, reqPath, resPath] = process.argv;
if (!simplifierJsPath || !reqPath || !resPath) {
  console.error('Usage: node simplify_meshopt.mjs <meshopt_simplifier.js> <req.bin> <res.bin>');
  process.exit(1);
}

const { MeshoptSimplifier } = await import(pathToFileURL(path.resolve(simplifierJsPath)).href);
await MeshoptSimplifier.ready;

const reqBuf = fs.readFileSync(reqPath);
let off = 0;
const numChunks = reqBuf.readUInt32LE(off);
off += 4;

const outChunks = [];
for (let c = 0; c < numChunks; c++) {
  const nVerts = reqBuf.readUInt32LE(off); off += 4;
  const nIndices = reqBuf.readUInt32LE(off); off += 4;
  const targetIdx = reqBuf.readUInt32LE(off); off += 4;
  const targetErr = reqBuf.readFloatLE(off); off += 4;
  const normWeight = reqBuf.readFloatLE(off); off += 4;
  const flagsMask = reqBuf.readUInt32LE(off); off += 4;

  const posBytes = nVerts * 12;
  const pos = new Float32Array(nVerts * 3);
  Buffer.from(pos.buffer).set(reqBuf.subarray(off, off + posBytes));
  off += posBytes;

  const nrm = new Float32Array(nVerts * 3);
  Buffer.from(nrm.buffer).set(reqBuf.subarray(off, off + posBytes));
  off += posBytes;

  const idxBytes = nIndices * 4;
  const indices = new Uint32Array(nIndices);
  Buffer.from(indices.buffer).set(reqBuf.subarray(off, off + idxBytes));
  off += idxBytes;

  const flags = (flagsMask & 1) ? ['LockBorder'] : ['Permissive'];
  let simplified;
  try {
    if (normWeight <= 0.25) {
      const [outIdx] = MeshoptSimplifier.simplify(
        indices,
        pos,
        3,
        targetIdx,
        targetErr,
        flags
      );
      simplified = outIdx;
    } else {
      const [outIdx] = MeshoptSimplifier.simplifyWithAttributes(
        indices,
        pos,
        3,
        nrm,
        3,
        [normWeight, normWeight, normWeight],
        null,
        targetIdx,
        targetErr,
        flags
      );
      simplified = outIdx;
    }
  } catch {
    const [outIdx] = MeshoptSimplifier.simplify(
      indices,
      pos,
      3,
      targetIdx,
      targetErr,
      flags
    );
    simplified = outIdx;
  }
  outChunks.push(simplified);
}

let totalOutBytes = 4;
for (const arr of outChunks) {
  totalOutBytes += 4 + arr.byteLength;
}
const resBuf = Buffer.allocUnsafe(totalOutBytes);
let wOff = 0;
resBuf.writeUInt32LE(outChunks.length, wOff); wOff += 4;
for (const arr of outChunks) {
  resBuf.writeUInt32LE(arr.length, wOff); wOff += 4;
  Buffer.from(arr.buffer, arr.byteOffset, arr.byteLength).copy(resBuf, wOff);
  wOff += arr.byteLength;
}
fs.writeFileSync(resPath, resBuf);
