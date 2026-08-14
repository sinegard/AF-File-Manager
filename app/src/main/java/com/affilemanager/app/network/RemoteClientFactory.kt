package com.affilemanager.app.network

class RemoteClientFactory {
    suspend fun connect(profile: NetworkProfile, secret: ConnectionSecret): RemoteClient = SerializedRemoteClient(when (profile.protocol) {
        NetworkProtocol.SFTP -> SftpRemoteClient.connect(profile, secret.password.copyOf(), secret.privateKeyPem.copyOf())
        NetworkProtocol.SMB -> SmbRemoteClient.connect(profile, secret.password.copyOf())
        NetworkProtocol.WEBDAV -> WebDavRemoteClient.connect(profile, secret.password.copyOf())
        NetworkProtocol.FTP, NetworkProtocol.FTPS -> FtpRemoteClient.connect(profile, secret.password.copyOf())
    })
}
