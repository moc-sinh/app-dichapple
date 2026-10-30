// Linux only: URLSession has no `bytes(for:)` there. This one answers from `FakeOpenAI`, so the
// streamed requests of Translator (compiled with its Apple-only part, see run.sh) run end to end.
#if canImport(FoundationNetworking)
import Foundation
import FoundationNetworking

enum FakeOpenAI {
    struct Answer {
        var status = 200
        var lines: [String]
        /// The connection drops before this line.
        var dropBefore: Int?
    }

    nonisolated(unsafe) static var answers: [Answer] = []
    nonisolated(unsafe) static var bodies: [[String: Any]] = []
}

struct FakeBytes {
    let lines: AsyncThrowingStream<String, Error>
}

extension URLSession {
    func bytes(for request: URLRequest) async throws -> (FakeBytes, URLResponse) {
        if let body = request.httpBody, let json = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] {
            FakeOpenAI.bodies.append(json)
        }
        guard !FakeOpenAI.answers.isEmpty else { throw URLError(.cannotConnectToHost) }
        let answer = FakeOpenAI.answers.removeFirst()
        let response = HTTPURLResponse(url: request.url!, statusCode: answer.status, httpVersion: nil, headerFields: nil)!
        let lines = AsyncThrowingStream<String, Error> { continuation in
            for (i, line) in answer.lines.enumerated() {
                if i == answer.dropBefore {
                    continuation.finish(throwing: URLError(.networkConnectionLost))
                    return
                }
                continuation.yield(line)
            }
            continuation.finish()
        }
        return (FakeBytes(lines: lines), response)
    }
}
#endif
