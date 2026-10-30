import Foundation
import Network
import Security
import Darwin

public struct HTTPRequest {
    public let method: String
    public let path: String
    public let headers: [String: String]
    public let body: Data
    public var token: String { (headers["authorization"] ?? "").hasPrefix("Bearer ") ? String(headers["authorization"]!.dropFirst(7)) : "" }
    public func object() throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: body) as? [String: Any] else { throw LinkError("BAD_REQUEST", "请求必须为 JSON 对象") }; return object
    }
}
public struct HTTPResponse {
    public var waitForEvent = false
    public var status: Int
    public var data: Data
    public var type: String
    public var extra: [String: String] = [:]
    public init(_ status: Int = 200, data: Data, type: String = "application/json; charset=utf-8", extra: [String: String] = [:]) { self.status = status; self.data = data; self.type = type; self.extra = extra }
    public static func json(_ value: [String: Any], status: Int = 200) -> HTTPResponse { HTTPResponse(status, data: (try? Wire.json(value)) ?? Data("{}".utf8)) }
}
public enum HTTPParser {
    public static func parse(_ data: Data) throws -> HTTPRequest? {
        guard data.count <= 40960 else { throw LinkError("TOO_LARGE", "请求过大") }
        guard let marker = data.range(of: Data("\r\n\r\n".utf8)) else {
            if data.count > 8192 { throw LinkError("TOO_LARGE", "请求头过大") }; return nil
        }
        guard marker.lowerBound <= 8192, let text = String(data: data[..<marker.lowerBound], encoding: .utf8) else { throw LinkError("BAD_REQUEST", "请求头错误") }
        let lines = text.components(separatedBy: "\r\n")
        let start = lines[0].split(separator: " ").map(String.init)
        guard start.count == 3, ["GET", "POST"].contains(start[0]), start[2] == "HTTP/1.1", start[1].hasPrefix("/"), !start[1].contains("#") else { throw LinkError("BAD_REQUEST", "无效的 HTTP 请求") }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let split = line.firstIndex(of: ":"), !line.hasPrefix(" ") && !line.hasPrefix("\t") else { throw LinkError("BAD_REQUEST", "请求头错误") }
            let name = line[..<split].lowercased(), value = line[line.index(after: split)...].trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty, headers[name] == nil else { throw LinkError("BAD_REQUEST", "重复的请求头") }; headers[name] = value
        }
        guard headers["transfer-encoding"] == nil, headers["host"] != nil else { throw LinkError("BAD_REQUEST", "不支持的请求格式") }
        let lengthText = headers["content-length"] ?? "0"
        guard lengthText.range(of: "^[0-9]+$", options: .regularExpression) != nil, let length = Int(lengthText), length <= 32768 else { throw LinkError("TOO_LARGE", "请求体错误") }
        if start[0] == "POST" && headers["content-length"] == nil { throw LinkError("BAD_REQUEST", "缺少请求长度") }
        let body = data[marker.upperBound...]
        if body.count < length { return nil }
        guard body.count == length else { throw LinkError("BAD_REQUEST", "不接受流水线请求") }
        return HTTPRequest(method: start[0], path: start[1], headers: headers, body: Data(body))
    }
}
public final class LocalServer {
    public private(set) var port: UInt16 = 0
    public var changed: (() -> Void)?
    public var failed: ((String) -> Void)?
    private var listener: NWListener?
    private var connections: [UUID: NWConnection] = [:]
    private var eventWaiters: [UUID: HTTPResponse] = [:]
    private let handler: (HTTPRequest) throws -> HTTPResponse
    public init(identity: SecIdentity? = nil, port: UInt16 = 0, handler: @escaping (HTTPRequest) throws -> HTTPResponse) throws {
        self.handler = handler
        let parameters: NWParameters
        if let identity {
            let tls = NWProtocolTLS.Options()
            guard let local = sec_identity_create(identity) else { throw LinkError("TLS", "无法配置 TLS") }
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, local)
            sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
            parameters = NWParameters(tls: tls)
        } else { parameters = .tcp }
        parameters.allowLocalEndpointReuse = true
        listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port) ?? .any)
    }
    public func start() {
        listener?.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready: self.port = self.listener?.port?.rawValue ?? 0; self.changed?()
            case .failed(let error): self.failed?(error.localizedDescription); self.stop()
            default: break
            }
        }
        listener?.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener?.start(queue: .main)
    }
    public func stop() {
        listener?.cancel(); listener = nil; port = 0
        for c in connections.values { c.cancel() }; connections.removeAll(); eventWaiters.removeAll()
    }
    /// One send attempt to each currently connected event listener. No queue or delivery retry.
    public func publishEvent(_ response: HTTPResponse) {
        let ids = Array(eventWaiters.keys)
        eventWaiters.removeAll()
        for id in ids { send(id, response) }
    }
    private func accept(_ connection: NWConnection) {
        guard connections.count < 32 else { connection.cancel(); return }
        let id = UUID(); connections[id] = connection
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.finish(id) }
            if case .cancelled = state { self?.connections.removeValue(forKey: id) }
        }
        connection.start(queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak self] in if self?.eventWaiters[id] == nil { self?.finish(id) } }
        receive(id, Data())
    }
    private func receive(_ id: UUID, _ buffer: Data) {
        guard let connection = connections[id] else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, complete, error in
            guard let self, self.connections[id] != nil else { return }
            let next = buffer + (data ?? Data())
            do {
                if let request = try HTTPParser.parse(next) {
                    let response = try self.handler(request)
                    if response.waitForEvent {
                        self.eventWaiters[id] = response
                        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
                            guard let self, let idle = self.eventWaiters.removeValue(forKey: id) else { return }
                            self.send(id, idle)
                        }
                    } else { self.send(id, response) }
                    return
                }
                if complete || error != nil { self.finish(id) } else { self.receive(id, next) }
            } catch {
                let e = error as? LinkError ?? LinkError("BAD_REQUEST", "请求无效")
                self.send(id, .json(["error": e.code, "message": e.message], status: 400))
            }
        }
    }
    private func send(_ id: UUID, _ response: HTTPResponse) {
        guard let connection = connections[id] else { return }
        let description = response.status == 200 ? "OK" : "Error"
        var header = "HTTP/1.1 \(response.status) \(description)\r\nContent-Type: \(response.type)\r\nContent-Length: \(response.data.count)\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"
        for (key, value) in response.extra { header += "\(key): \(value)\r\n" }
        header += "\r\n"
        connection.send(content: Data(header.utf8) + response.data, completion: .contentProcessed { [weak self] _ in self?.finish(id) })
    }
    private func finish(_ id: UUID) { eventWaiters.removeValue(forKey: id); connections.removeValue(forKey: id)?.cancel() }
}
public enum LocalAddress {
    public static func candidates() -> [(String, String)] {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0, let first else { return [] }; defer { freeifaddrs(first) }
        var result: [(String, String)] = []; var p: UnsafeMutablePointer<ifaddrs>? = first
        while let current = p {
            defer { p = current.pointee.ifa_next }
            let interface = current.pointee
            guard let addr = interface.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET), interface.ifa_flags & UInt32(IFF_UP) != 0 else { continue }
            let name = String(cString: interface.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let host = String(cString: buffer)
            if isPrivate(host) { result.append((name, host)) }
        }
        return result.sorted { ($0.0.hasPrefix("en") ? 0 : 1, $0.0) < ($1.0.hasPrefix("en") ? 0 : 1, $1.0) }
    }
    public static func isPrivate(_ host: String) -> Bool {
        let parts = host.split(separator: "."); guard parts.count == 4 else { return false }
        let values = parts.compactMap { Int($0) }; guard values.count == 4, values.allSatisfy({ 0...255 ~= $0 }) else { return false }
        return values[0] == 10 || values[0] == 192 && values[1] == 168 || values[0] == 172 && (16...31).contains(values[1])
    }
}
