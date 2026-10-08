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

## slf4j-api / slf4j-nop 2.0.18 — MIT

Logging API and no-output runtime binding used by the reader/network dependencies. Project: https://www.slf4j.org/

## AndroidX Room 2.8.4 — Apache License 2.0

Used for the persistent, incremental storage index. Project: https://developer.android.com/jetpack/androidx/releases/room

## SSHJ 0.40.0 — Apache License 2.0

Used for SFTP/SSH client access. Project: https://github.com/hierynomus/sshj

## Apache MINA SSHD 2.19.0 — Apache License 2.0

Used for the embedded SFTP-only SSH server on the phone (`sshd-core` + `sshd-sftp`). Project: https://mina.apache.org/sshd-project/

## Google Play services Auth 21.6.0

Used for Google Identity Services `AuthorizationClient` in the Google Drive connection flow.
Artifact: `com.google.android.gms:play-services-auth:21.6.0` from Google Maven.
Use and redistribution are subject to the applicable Google Play services / Google APIs SDK terms published by Google; this project does not vendor or modify the library source.

## AndroidX / Jetpack / Compose — Apache License 2.0

The application uses AndroidX Activity, Compose, DocumentFile, ExifInterface, Lifecycle, Media3 and Room components. AndroidX libraries are distributed under the Apache License 2.0 unless an individual artifact states otherwise.

## Apache Commons Net 3.13.0 — Apache License 2.0

Used for FTP/FTPS client access. Project: https://commons.apache.org/proper/commons-net/

## Apache Commons Compress 1.28.0 — Apache License 2.0

Used for ZIP/TAR/7z and compressed stream formats. Project: https://commons.apache.org/proper/commons-compress/

## Bouncy Castle 1.85 line — MIT-style Bouncy Castle License

`bcprov-jdk18on` 1.85.2 and `bcpkix-jdk18on` 1.85 provide cryptographic primitives required by the SSH/SMB stack. The Bouncy Castle license is an MIT-style permissive license. Project: https://www.bouncycastle.org/

## XZ for Java 1.12 — 0BSD

Used by the XZ support in the archive stack. XZ for Java 1.10 and newer are distributed under the BSD Zero Clause License. Project: https://tukaani.org/xz/java.html

## Runtime dependency inventory note

The sections above document the direct runtime libraries declared by `app/build.gradle.kts`. Test-only dependencies (for example JUnit and AndroidX test artifacts) are not shipped as application runtime components and are therefore not part of this runtime notice inventory.
