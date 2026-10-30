import XCTest
@testable import SoundLinkCore

final class EventDeliveryTests: XCTestCase {
    func testEventDeliveredOnlyToCurrentAuthenticatedWaiter() throws {
        let ready = expectation(description: "listener"), delivered = expectation(description: "event")
        var server: LocalServer!
        server = try LocalServer { request in
            guard request.token == "fixture-session" else { return .json(["error": "SESSION_EXPIRED"], status: 401) }
            var response = HTTPResponse.json(["event": "idle"]); response.waitForEvent = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                server.publishEvent(.json(["event": "unpaired", "pairing_id": "current-pairing"]))
            }
            return response
        }
        defer { server.stop() }
        server.changed = { ready.fulfill() }; server.start()
        wait(for: [ready], timeout: 3)
        // A notice issued without a listener is discarded; it must not leak into a future pairing.
        server.publishEvent(.json(["event": "unpaired", "pairing_id": "old-pairing"]))
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(server.port)/v1/events")!)
        request.httpMethod = "POST"; request.httpBody = Data("{}".utf8)
        request.setValue("Bearer fixture-session", forHTTPHeaderField: "Authorization")
        URLSession.shared.dataTask(with: request) { data, response, error in
            XCTAssertNil(error); XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
            let result = try? JSONSerialization.jsonObject(with: data ?? Data()) as? [String: String]
            XCTAssertEqual(result?["event"], "unpaired"); XCTAssertEqual(result?["pairing_id"], "current-pairing")
            delivered.fulfill()
        }.resume()
        wait(for: [delivered], timeout: 3)
    }
}
