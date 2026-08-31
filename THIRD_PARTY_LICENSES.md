# Third-party components

## djvu-rs 0.27.0 — MIT

Project: https://github.com/matyushkin/djvu-rs

The imported Chitets 0.7.3.1 source does **not** bundle the Rust or WebAssembly runtime. Its Pure Java DjVu decoder was implemented/adapted using the public DjVu v3 specification and the MIT-licensed clean implementation in djvu-rs as a reference. No DjVuLibre/GPL source is bundled.

MIT License

Copyright (c) 2026 Lev Matyushkin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


## PDF reflow parser

The PDF text/reflow parser in the imported Chitets source is implemented directly in the project sources and does not bundle PDFBox, MuPDF, Poppler or another PDF SDK. Hybrid crops use Android's platform `android.graphics.pdf.PdfRenderer`; no additional third-party PDF library is bundled.

## junrar 8.1.0

Used for CBR/RAR comic archive reading. Licensed under the UnRAR License; see the upstream project/package for the complete terms.

## SMBJ 0.15.0 — Apache License 2.0

Used for SMB2/SMB3 client access. Project: https://github.com/hierynomus/smbj

## CodeLibs JCIFS 2.1.40 — LGPL-2.1

Used only to enumerate the file shares exposed by an SMB2 server; file browsing and transfers remain on SMBJ. Project: https://github.com/codelibs/jcifs

## slf4j-api / slf4j-nop 2.0.18

Logging API and no-output runtime binding used by the reader/network dependencies.

## AndroidX Room 2.8.4 — Apache License 2.0

Used for the persistent, incremental storage index. Project: https://developer.android.com/jetpack/androidx/releases/room

## SSHJ 0.40.0 — Apache License 2.0

Used for SFTP/SSH client access. Project: https://github.com/hierynomus/sshj

## Apache MINA SSHD 2.19.0 — Apache License 2.0

Used for the embedded SFTP-only SSH server on the phone (`sshd-core` + `sshd-sftp`). Project: https://mina.apache.org/sshd-project/
