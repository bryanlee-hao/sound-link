import Foundation
import CoreBluetooth
import SoundLinkCore

final class MacDiscovery: NSObject, CBPeripheralManagerDelegate {
    private var manager: CBPeripheralManager!
    private let identity: DeviceIdentity
    let serviceUUID: UUID
    let name: String
    private var published = false
    private var desired = true
    private var hosts: [String] = []
    private var port: UInt16 = 0
    private(set) var revision: UInt64 = 1
    private var responses: [UUID: (Data, TimeInterval)] = [:]
    private(set) var status = "蓝牙准备中"
    private(set) var reads = 0
    private(set) var lastRead: Date?
    var changed: (() -> Void)?

    init(identity: DeviceIdentity) {
        self.identity = identity
        serviceUUID = DiscoveryWire.serviceUUID(pin: identity.pin)
        name = String(format: "Sound Link %04u", UInt32(identity.pin.prefix(8), radix: 16)! % 10000)
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }
    func update(hosts: [String], port: UInt16) {
        let next = Array(hosts.prefix(4))
        if self.hosts != next || self.port != port {
            self.hosts = next; self.port = port; revision += 1
        }
    }
    func setAvailable(_ available: Bool) {
        desired = available; responses.removeAll(); revision += 1
        if available { advertise() }
        else { manager.stopAdvertising(); setStatus("已暂停广播与连接") }
    }
    func stop() { desired = false; manager.stopAdvertising(); manager.removeAllServices(); responses.removeAll() }
    private func setStatus(_ text: String) { status = text; changed?() }
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        published = false; responses.removeAll()
        switch peripheral.state {
        case .poweredOn:
            peripheral.removeAllServices()
            let service = CBMutableService(type: CBUUID(nsuuid: serviceUUID), primary: true)
            service.characteristics = [
                CBMutableCharacteristic(type: CBUUID(nsuuid: DiscoveryWire.challengeUUID), properties: [.write], value: nil, permissions: [.writeable]),
                CBMutableCharacteristic(type: CBUUID(nsuuid: DiscoveryWire.endpointUUID), properties: [.read], value: nil, permissions: [.readable])
            ]
            peripheral.add(service); setStatus("正在发布蓝牙地址服务")
        case .poweredOff: setStatus("Mac 蓝牙已关闭")
        case .unauthorized: setStatus("请在系统设置允许 Sound Link 使用蓝牙")
        case .unsupported: setStatus("此 Mac 不支持蓝牙发现")
        default: setStatus("蓝牙准备中")
        }
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error { setStatus("蓝牙服务发布失败：\(error.localizedDescription)"); return }
        published = true; advertise()
    }
    private func advertise() {
        guard manager.state == .poweredOn, published, desired else { return }
        manager.stopAdvertising()
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(nsuuid: serviceUUID)], CBAdvertisementDataLocalNameKey: name])
    }
    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        setStatus(error.map { "广播失败：\($0.localizedDescription)" } ?? "正在广播 · \(name)")
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let request = requests.first else { return }
        guard requests.count == 1, request.characteristic.uuid == CBUUID(nsuuid: DiscoveryWire.challengeUUID), request.offset == 0, let nonce = request.value, nonce.count == 16 else {
            peripheral.respond(to: request, withResult: .invalidAttributeValueLength); return
        }
        responses = responses.filter { $0.value.1 > MonotonicTime.now }
        guard responses.count < 16 || responses[request.central.identifier] != nil else {
            peripheral.respond(to: request, withResult: .insufficientResources); return
        }
        do {
            let proof = try identity.endpointProof(nonce: nonce, revision: revision, port: port, hosts: hosts, available: desired && port > 0 && !hosts.isEmpty)
            responses[request.central.identifier] = (proof, MonotonicTime.now + 15)
            peripheral.respond(to: request, withResult: .success)
        } catch { peripheral.respond(to: request, withResult: .unlikelyError) }
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == CBUUID(nsuuid: DiscoveryWire.endpointUUID),
              let response = responses[request.central.identifier], response.1 > MonotonicTime.now else {
            peripheral.respond(to: request, withResult: .insufficientAuthorization); return
        }
        guard request.offset <= response.0.count else { peripheral.respond(to: request, withResult: .invalidOffset); return }
        request.value = response.0.subdata(in: request.offset..<response.0.count)
        peripheral.respond(to: request, withResult: .success)
        if request.offset == 0 { reads += 1; lastRead = Date(); changed?() }
    }
}
