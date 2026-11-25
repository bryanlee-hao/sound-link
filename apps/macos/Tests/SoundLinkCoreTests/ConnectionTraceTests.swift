import XCTest
import Network
@testable import SoundLinkCore

final class ConnectionTraceTests: XCTestCase {
    func testFastServedConnectionIsSilent() {
        // Arrange
        let trace = ConnectionTrace(acceptedAt: 100, liveAtAccept: 2).ready(at: 100.05).request(at: 100.1, path: "/v1/ping")

        // Act
        let report = trace.report(outcome: "served", now: 100.2, live: 1)

        // Assert
        XCTAssertNil(report)
    }

    func testSlowHandshakeIsReportedEvenWhenServed() throws {
        let trace = ConnectionTrace(acceptedAt: 100, liveAtAccept: 3).ready(at: 101.5).request(at: 101.6, path: "/v1/ping")

        let report = try XCTUnwrap(trace.report(outcome: "served", now: 101.7, live: 2))

        XCTAssertEqual(report["outcome"] as? String, "served")
        XCTAssertEqual(report["handshake_ms"] as? Int, 1500)
        XCTAssertEqual(report["request_ms"] as? Int, 1600)
        XCTAssertEqual(report["total_ms"] as? Int, 1700)
        XCTAssertEqual(report["live_at_accept"] as? Int, 3)
        XCTAssertEqual(report["path"] as? String, "/v1/ping")
    }

    func testStuckHandshakeReportsMissingStagesAsMinusOne() throws {
        let trace = ConnectionTrace(acceptedAt: 100, liveAtAccept: 1)

        let report = try XCTUnwrap(trace.report(outcome: "idle_timeout", now: 112, live: 0))

        XCTAssertEqual(report["handshake_ms"] as? Int, -1)
        XCTAssertEqual(report["request_ms"] as? Int, -1)
        XCTAssertEqual(report["total_ms"] as? Int, 12000)
        XCTAssertNil(report["path"])
    }

    func testPathDropsQueryAndIsBounded() {
        let long = "/" + String(repeating: "a", count: 200)

        let withQuery = ConnectionTrace(acceptedAt: 0, liveAtAccept: 1).request(at: 0, path: "/v1/ping?token=secret").path
        let bounded = ConnectionTrace(acceptedAt: 0, liveAtAccept: 1).request(at: 0, path: long).path

        XCTAssertEqual(withQuery, "/v1/ping")
        XCTAssertEqual(bounded?.count, ConnectionTrace.maxPathLength)
    }

    func testFirstReadyAndRequestTimesAreKept() {
        let trace = ConnectionTrace(acceptedAt: 0, liveAtAccept: 1).ready(at: 1).ready(at: 5).request(at: 2, path: "/a").request(at: 9, path: "/a")

        XCTAssertEqual(trace.readyAt, 1)
        XCTAssertEqual(trace.requestAt, 2)
    }

    func testServerReportsPeerClosedBeforeRequestButNotServedRequests() throws {
        // Arrange
        let ready = expectation(description: "listener"), served = expectation(description: "served"), dropped = expectation(description: "dropped")
        var reports: [[String: Any]] = []
        let server = try LocalServer { _ in .json(["ok": true]) }
        defer { server.stop() }
        server.trace = { type, data in
            guard type == "server_connection" else { return }
            reports.append(data)
            if data["outcome"] as? String == "peer_closed" { dropped.fulfill() }
        }
        server.changed = { ready.fulfill() }
        server.start()
        wait(for: [ready], timeout: 3)

        // Act: one normal request, then a raw connection that closes without sending anything.
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(server.port)/v1/ping")!)
        request.httpMethod = "POST"; request.httpBody = Data("{}".utf8)
        URLSession.shared.dataTask(with: request) { _, response, _ in
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200); served.fulfill()
        }.resume()
        wait(for: [served], timeout: 3)
        let raw = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: server.port)!, using: .tcp)
        raw.stateUpdateHandler = { state in if case .ready = state { raw.cancel() } }
        raw.start(queue: .global())
        wait(for: [dropped], timeout: 3)

        // Assert
        XCTAssertEqual(reports.count, 1, "served fast request must stay silent")
        XCTAssertEqual(reports.first?["request_ms"] as? Int, -1)
        XCTAssertNil(reports.first?["path"])
    }
}
