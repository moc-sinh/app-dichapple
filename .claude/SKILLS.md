# Bộ skill đã nạp cho Claude Code

Các skill trong `.claude/skills/` được Claude Code tự nạp ở mọi phiên làm việc trên repo này (kể cả phiên trên cloud).

| Bộ | Skill | Nguồn / giấy phép |
|---|---|---|
| superpowers 7.0.0 | `using-superpowers`, `brainstorming`, `writing-plans`, `executing-plans`, `subagent-driven-development`, `dispatching-parallel-agents`, `test-driven-development`, `systematic-debugging`, `verification-before-completion`, `requesting-code-review`, `receiving-code-review`, `using-git-worktrees`, `finishing-a-development-branch`, `writing-skills`, `diagnosing-superpowers` | obra/superpowers, MIT |
| ponytail 5.2.0 | `ponytail`, `ponytail-review`, `ponytail-audit`, `ponytail-debt`, `ponytail-gain`, `ponytail-help` | DietrichGebert, MIT |
| impeccable 4.5.2 | `impeccable` | pbakaus/impeccable, Apache-2.0 |
| graphify 0.9.90 | `graphify` | Safi Shamsi, Apache-2.0 / MIT |
| Understand-Anything 2.9.7 | `understand`, `understand-chat`, `understand-diff`, `understand-domain`, `understand-explain`, `understand-onboard`, `understand-dashboard`, `understand-figma`, `understand-knowledge` | MIT; mã plugin nằm ở `.claude/vendor/understand-anything-plugin` |

Giấy phép gốc nằm trong `.claude/licenses/`.

## Khác với bản cài plugin gốc

- **Không cài hook.** Các bộ gốc có hook tự chạy code khi mở phiên, sau mỗi lần sửa file hoặc khi kết thúc lượt. Ở đây chỉ nạp skill, nên skill chỉ chạy khi được gọi (ví dụ `/brainstorming`, `/ponytail`, `/impeccable audit`) hoặc khi Claude thấy mô tả của skill khớp với việc đang làm.
  - superpowers: hook gốc chèn `using-superpowers` vào đầu mỗi phiên. Muốn dùng thì gọi `/using-superpowers`.
  - ponytail: hook gốc bật sẵn chế độ ponytail. Muốn dùng thì gọi `/ponytail`.
  - impeccable: hook gốc chạy bộ kiểm tra giao diện sau mỗi lần sửa file.
  - Understand-Anything: hook gốc tự cập nhật knowledge graph.
- **Understand-Anything:** đã thêm đường dẫn `.claude/vendor/understand-anything-plugin` vào danh sách tìm plugin root trong `understand`, `understand-domain`, `understand-dashboard`.
- **Cần mạng khi dùng lần đầu:**
  - `graphify` tự cài gói `graphifyy` từ PyPI.
  - `impeccable` tải engine từ GitHub Releases.
  - Understand-Anything cài và build gói Node bằng pnpm.
