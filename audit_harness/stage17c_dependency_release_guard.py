from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
licenses = (ROOT / 'THIRD_PARTY_LICENSES.md').read_text(encoding='utf-8')

def need(value, where, msg):
    if value not in where:
        raise AssertionError(msg)

# Release-security pins selected by the audit.
need('org.tukaani:xz:1.12', gradle, 'XZ must stay on 1.12 (1.10/1.11 encoder bug)')
need('org.bouncycastle:bcprov-jdk18on:1.85.2', gradle, 'BC provider pin drifted')
need('org.bouncycastle:bcpkix-jdk18on:1.85', gradle, 'BC PKIX pin drifted')
need('com.github.junrar:junrar:8.1.0', gradle, 'junrar audited version drifted')
need('org.apache.commons:commons-compress:1.28.0', gradle, 'Commons Compress audited version drifted')
need('commons-net:commons-net:3.13.0', gradle, 'Commons Net audited version drifted')

# Every direct non-test runtime dependency family must have a notice section.
coverage = {
    'androidx.': 'AndroidX / Jetpack / Compose',
    'commons-net:commons-net': 'Apache Commons Net 3.13.0',
    'com.hierynomus:smbj': 'SMBJ 0.15.0',
    'org.codelibs:jcifs': 'CodeLibs JCIFS 2.1.40',
    'com.hierynomus:sshj': 'SSHJ 0.40.0',
    'org.apache.sshd:sshd-core': 'Apache MINA SSHD 2.19.0',
    'org.apache.sshd:sshd-sftp': 'Apache MINA SSHD 2.19.0',
    'org.bouncycastle:bcprov-jdk18on': 'Bouncy Castle 1.85 line',
    'org.bouncycastle:bcpkix-jdk18on': 'Bouncy Castle 1.85 line',
    'org.apache.commons:commons-compress': 'Apache Commons Compress 1.28.0',
    'com.google.android.gms:play-services-auth': 'Google Play services Auth 21.6.0',
    'org.tukaani:xz': 'XZ for Java 1.12',
    'com.github.junrar:junrar': 'junrar 8.1.0',
    'org.slf4j:slf4j-nop': 'slf4j-api / slf4j-nop 2.0.18',
}
for coord, notice in coverage.items():
    need(coord, gradle, f'Expected runtime dependency {coord} not found in Gradle')
    need(notice, licenses, f'License notice missing for {coord}')

# Packaging deliberately strips duplicated embedded META-INF legal files, so the
# project-level inventory must remain present in the source distribution.
need('META-INF/LICENSE', gradle, 'Packaging legal-file exclusion changed; review inventory assumptions')
need('# Third-party components', licenses, 'Third-party notice file malformed')

print('STAGE17C_DEPENDENCY_RELEASE_GUARD_PASS')
