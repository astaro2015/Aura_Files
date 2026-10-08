package com.aurafiles.app.data

import com.aurafiles.app.model.SftpServerConfig
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.AccessDeniedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.Principal
import java.util.concurrent.ConcurrentHashMap
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.DirectoryHandle
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy

/** Embedded SFTP-only SSH server rooted in one real local directory. */
class SftpServer(
    private val rootPath: Path,
    private val hostKeyPath: Path,
    private val config: SftpServerConfig,
    private val onClientCountChanged: (Int) -> Unit,
) {
    private var server: SshServer? = null
    private val authenticatedSessions = ConcurrentHashMap.newKeySet<Session>()

    fun start(): Int {
        check(server == null) { "SFTP-сервер уже запущен" }

        val sshd = SshServer.setUpDefaultServer().apply {
            setHost("0.0.0.0")
            setPort(config.port)

            val keyProvider = SimpleGeneratorHostKeyProvider(hostKeyPath).apply {
                setAlgorithm("RSA")
                setKeySize(3072)
                setOverwriteAllowed(false)
            }
            setKeyPairProvider(keyProvider)

            setPasswordAuthenticator(org.apache.sshd.server.auth.password.PasswordAuthenticator { username, password, session ->
                isLocalPeer(session.clientAddress) &&
                    secureEquals(username, config.username) &&
                    secureEquals(password, config.password)
            })

            // No ShellFactory and no CommandFactory are installed: this endpoint is SFTP only.
            setFileSystemFactory(VirtualFileSystemFactory(rootPath))
            val sftpFactory = SftpSubsystemFactory.Builder()
                .withFileSystemAccessor(VaultFilteringSftpAccessor(config.readOnly))
                .build()
            setSubsystemFactories(listOf(sftpFactory))

            addSessionListener(object : SessionListener {
                override fun sessionEvent(session: Session, event: SessionListener.Event) {
                    if (event == SessionListener.Event.Authenticated && authenticatedSessions.add(session)) {
                        onClientCountChanged(authenticatedSessions.size)
                    }
                }

                override fun sessionClosed(session: Session) {
                    if (authenticatedSessions.remove(session)) {
                        onClientCountChanged(authenticatedSessions.size)
                    }
                }
            })
        }

        return try {
            sshd.start()
            server = sshd
            sshd.port
        } catch (error: Throwable) {
            runCatching { sshd.stop(true) }
            throw error
        }
    }

    fun stop() {
        val sshd = server ?: return
        server = null
        runCatching { sshd.stop(true) }
        authenticatedSessions.clear()
        onClientCountChanged(0)
    }

    private fun isLocalPeer(address: SocketAddress?): Boolean {
        val inet = (address as? InetSocketAddress)?.address ?: return false
        if (inet.isLoopbackAddress || inet.isLinkLocalAddress || inet.isSiteLocalAddress) return true
        if (inet is Inet6Address) {
            val first = inet.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
            return first and 0xfe == 0xfc // fc00::/7 — IPv6 unique local address
        }
        return false
    }

    private fun secureEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(Charsets.UTF_8),
        right.toByteArray(Charsets.UTF_8),
    )

    private class VaultFilteringSftpAccessor(
        private val readOnly: Boolean,
    ) : SftpFileSystemAccessor {
        private fun denied(path: Path): Nothing =
            throw AccessDeniedException(path.toString(), null, "Путь зарезервирован Aura Files")

        private fun readOnlyDenied(path: Path): Nothing =
            throw AccessDeniedException(path.toString(), null, "SFTP-сервер работает только на чтение")

        private fun isReservedPath(path: Path): Boolean =
            path.normalize().any { segment ->
                AuraVault.isVaultFolder(segment.toString()) || segment.toString() == AURA_TRASH_FOLDER
            }

        private fun ensureVisible(path: Path) {
            if (isReservedPath(path)) denied(path)
        }

        override fun resolveLocalFilePath(
            subsystem: SftpSubsystemProxy,
            rootDir: Path,
            remotePath: String,
        ): Path {
            val resolved = SftpFileSystemAccessor.DEFAULT.resolveLocalFilePath(subsystem, rootDir, remotePath)
            ensureVisible(resolved)
            return resolved
        }

        override fun openDirectory(
            subsystem: SftpSubsystemProxy,
            dirHandle: DirectoryHandle?,
            dir: Path,
            handle: String?,
            vararg linkOptions: LinkOption,
        ): DirectoryStream<Path> {
            ensureVisible(dir)
            return Files.newDirectoryStream(dir) { child ->
                val name = child.fileName?.toString()
                name != null && !AuraVault.isVaultFolder(name) && name != AURA_TRASH_FOLDER
            }
        }

        override fun openFile(
            subsystem: SftpSubsystemProxy,
            fileHandle: FileHandle?,
            file: Path,
            handle: String?,
            options: Set<OpenOption>,
            vararg attrs: FileAttribute<*>,
        ): SeekableByteChannel {
            ensureVisible(file)
            val writes = options.any {
                it == StandardOpenOption.WRITE ||
                    it == StandardOpenOption.APPEND ||
                    it == StandardOpenOption.CREATE ||
                    it == StandardOpenOption.CREATE_NEW ||
                    it == StandardOpenOption.TRUNCATE_EXISTING ||
                    it == StandardOpenOption.DELETE_ON_CLOSE
            }
            if (readOnly && writes) readOnlyDenied(file)
            return SftpFileSystemAccessor.DEFAULT.openFile(subsystem, fileHandle, file, handle, options, *attrs)
        }

        override fun setFileAttribute(
            subsystem: SftpSubsystemProxy,
            file: Path,
            view: String,
            attribute: String,
            value: Any,
            vararg options: LinkOption,
        ) {
            ensureVisible(file)
            if (readOnly) readOnlyDenied(file)
            SftpFileSystemAccessor.DEFAULT.setFileAttribute(subsystem, file, view, attribute, value, *options)
        }

        override fun setFileOwner(
            subsystem: SftpSubsystemProxy,
            file: Path,
            value: Principal,
            vararg options: LinkOption,
        ) {
            ensureVisible(file)
            if (readOnly) readOnlyDenied(file)
            SftpFileSystemAccessor.DEFAULT.setFileOwner(subsystem, file, value, *options)
        }

        override fun setGroupOwner(
            subsystem: SftpSubsystemProxy,
            file: Path,
            value: Principal,
            vararg options: LinkOption,
        ) {
            ensureVisible(file)
            if (readOnly) readOnlyDenied(file)
            SftpFileSystemAccessor.DEFAULT.setGroupOwner(subsystem, file, value, *options)
        }

        override fun setFilePermissions(
            subsystem: SftpSubsystemProxy,
            file: Path,
            perms: Set<PosixFilePermission>,
            vararg options: LinkOption,
        ) {
            ensureVisible(file)
            if (readOnly) readOnlyDenied(file)
            SftpFileSystemAccessor.DEFAULT.setFilePermissions(subsystem, file, perms, *options)
        }

        override fun setFileAccessControl(
            subsystem: SftpSubsystemProxy,
            file: Path,
            acl: List<AclEntry>,
            vararg options: LinkOption,
        ) {
            ensureVisible(file)
            if (readOnly) readOnlyDenied(file)
            SftpFileSystemAccessor.DEFAULT.setFileAccessControl(subsystem, file, acl, *options)
        }

        override fun createDirectory(subsystem: SftpSubsystemProxy, path: Path) {
            ensureVisible(path)
            if (readOnly) readOnlyDenied(path)
            SftpFileSystemAccessor.DEFAULT.createDirectory(subsystem, path)
        }

        override fun createLink(
            subsystem: SftpSubsystemProxy,
            link: Path,
            existing: Path,
            symLink: Boolean,
        ) {
            ensureVisible(link)
            ensureVisible(existing)
            if (readOnly) readOnlyDenied(link)
            SftpFileSystemAccessor.DEFAULT.createLink(subsystem, link, existing, symLink)
        }

        override fun renameFile(
            subsystem: SftpSubsystemProxy,
            oldPath: Path,
            newPath: Path,
            opts: Collection<CopyOption>,
        ) {
            ensureVisible(oldPath)
            ensureVisible(newPath)
            if (readOnly) readOnlyDenied(oldPath)
            SftpFileSystemAccessor.DEFAULT.renameFile(subsystem, oldPath, newPath, opts)
        }

        override fun copyFile(
            subsystem: SftpSubsystemProxy,
            src: Path,
            dst: Path,
            opts: Collection<CopyOption>,
        ) {
            ensureVisible(src)
            ensureVisible(dst)
            if (readOnly) readOnlyDenied(dst)
            SftpFileSystemAccessor.DEFAULT.copyFile(subsystem, src, dst, opts)
        }

        override fun removeFile(subsystem: SftpSubsystemProxy, path: Path, isDirectory: Boolean) {
            ensureVisible(path)
            if (readOnly) readOnlyDenied(path)
            SftpFileSystemAccessor.DEFAULT.removeFile(subsystem, path, isDirectory)
        }

        override fun applyExtensionFileAttributes(
            subsystem: SftpSubsystemProxy,
            file: Path,
            extensions: Map<String, ByteArray>,
            vararg options: LinkOption,
        ) {
            ensureVisible(file)
            if (readOnly) readOnlyDenied(file)
            SftpFileSystemAccessor.DEFAULT.applyExtensionFileAttributes(subsystem, file, extensions, *options)
        }
    }

}
