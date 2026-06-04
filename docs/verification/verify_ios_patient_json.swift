/*
---
type: transitional
created: 2026-06-03
expires_when: Android 患者信息与模板对齐功能完成、验证通过并经用户确认验收后
related_to: ../android-ios-patient-template-alignment-plan.md
---
*/

import Foundation

enum FieldValue: Codable, Equatable {
    case string(String)
    case bool(Bool)
    case stringArray([String])
    case date(Date)

    enum CodingKeys: String, CodingKey {
        case type, value
    }

    enum ValueType: String, Codable {
        case string, bool, stringArray, date
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(ValueType.self, forKey: .type)
        switch type {
        case .string:
            self = .string(try container.decode(String.self, forKey: .value))
        case .bool:
            self = .bool(try container.decode(Bool.self, forKey: .value))
        case .stringArray:
            self = .stringArray(try container.decode([String].self, forKey: .value))
        case .date:
            self = .date(try container.decode(Date.self, forKey: .value))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .string(let value):
            try container.encode(ValueType.string, forKey: .type)
            try container.encode(value, forKey: .value)
        case .bool(let value):
            try container.encode(ValueType.bool, forKey: .type)
            try container.encode(value, forKey: .value)
        case .stringArray(let value):
            try container.encode(ValueType.stringArray, forKey: .type)
            try container.encode(value, forKey: .value)
        case .date(let value):
            try container.encode(ValueType.date, forKey: .type)
            try container.encode(value, forKey: .value)
        }
    }
}

struct PatientInfoData: Codable {
    var templateId: String
    var templateName: String
    var patientId: String
    var sessionId: String
    var recordingStartTime: Date
    var submitTime: Date
    var fields: [String: FieldValue]
    var photoFileName: String?
}

let json = """
{
  "templateId": "cardio_cerebrovascular",
  "templateName": "心脑血管通用",
  "patientId": "MR001",
  "sessionId": "Session_1710000000000",
  "recordingStartTime": "2026-06-03T09:00:00Z",
  "submitTime": "2026-06-03T09:10:00Z",
  "fields": {
    "patientId": { "type": "string", "value": "MR001" },
    "patientName": { "type": "string", "value": "张三" },
    "examDate": { "type": "date", "value": "2026-06-03T00:00:00Z" },
    "diagnoses": {
      "type": "stringArray",
      "value": ["I10.x00x002 高血压", "I25.103 冠状动脉粥样硬化性心脏病"]
    }
  },
  "photoFileName": "patient_photo_1710000000000.jpg"
}
"""

let decoder = JSONDecoder()
decoder.dateDecodingStrategy = .iso8601
let data = try decoder.decode(PatientInfoData.self, from: Data(json.utf8))

guard data.templateId == "cardio_cerebrovascular",
      data.patientId == "MR001",
      data.sessionId == "Session_1710000000000" else {
    fatalError("Decoded top-level fields do not match")
}

guard case .date(let examDate)? = data.fields["examDate"],
      ISO8601DateFormatter().string(from: examDate) == "2026-06-03T00:00:00Z" else {
    fatalError("Date field did not decode as iOS FieldValue.date")
}

guard case .stringArray(let diagnoses)? = data.fields["diagnoses"],
      diagnoses.count == 2,
      diagnoses[0] == "I10.x00x002 高血压" else {
    fatalError("Diagnosis list did not decode as iOS FieldValue.stringArray")
}

print("iOS patient JSON decode OK")
