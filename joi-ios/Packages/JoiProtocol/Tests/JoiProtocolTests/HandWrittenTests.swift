import Testing
@testable import JoiProtocol

/// What the samples do not cover: a newer server's values, dates, frames that are built and frames that are wrong.
@Suite struct HandWrittenTests {

    @Test func anEnumValueOfANewerServerIsKept() throws {
        let tier = try decode(ToolTier.self, "\"telepathy\"")
        #expect(tier == .unknown("telepathy"))
        #expect(tier.rawValue == "telepathy")
        #expect(try decode(ToolTier.self, "\"ask\"") == .ask)
        #expect(try encodeToString(ToolTier.deny) == "\"deny\"")
    }

    @Test func aFrameOfAnUnknownTypeIsKeptNotFailed() throws {
        let frame = try decode(Frame.self, #"{"type": "hologram", "data": {"depth": 3}}"#)
        #expect(frame.messageType == .unknown("hologram"))
        #expect(try frame.typedData() == .unknown(type: "hologram", data: ["depth": .int(3)]))
    }

    @Test func aKnownFrameWithoutItsDataThrows() throws {
        let frame = try decode(Frame.self, #"{"type": "say"}"#)
        #expect(throws: JoiProtocolError.missingData(type: "say")) { try frame.typedData() }
    }

    @Test func pingHasNoData() throws {
        let frame = try decode(Frame.self, #"{"type": "ping", "id": "p1"}"#)
        #expect(try frame.typedData() == .ping)
        #expect(Frame.pong(ref: "p1").ref == "p1")
    }

    @Test func aBuiltFrameCarriesItsDataAndComesBackTyped() throws {
        let frame = try Frame.say(Say(text: "Hi", speak: false), id: "t1")
        #expect(frame.type == "say")
        #expect(try encodeToString(frame) == #"{"data":{"speak":false,"text":"Hi"},"id":"t1","type":"say"}"#)
        #expect(try frame.typedData() == .say(Say(text: "Hi", speak: false)))
    }

    @Test func datesWithAndWithoutFractionsKeepTheirForm() throws {
        let whole = #"{"id":"m1","sentAt":"2026-11-18T19:00:00Z","type":"user"}"#
        let fraction = #"{"id":"m1","sentAt":"2026-11-18T19:00:00.250Z","type":"user"}"#
        try roundTrip(ChatEntry.self, whole)
        try roundTrip(ChatEntry.self, fraction)
    }

    @Test func theVersionIsTheServers() {
        #expect(JoiProtocolInfo.version.hasPrefix("1."))
        #expect(JoiProtocolInfo.node == "/api/v1/node")
    }
}
