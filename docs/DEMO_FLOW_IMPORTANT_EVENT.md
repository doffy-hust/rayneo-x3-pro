# Kịch Bản Demo Sự Kiện Quan Trọng - TapLinkX3

Tài liệu này dùng cho sự kiện quan trọng của công ty, tập trung vào các "wow moment" để thể hiện định tuyến giọng nói thông minh, nhanh và đáng tin cậy.

## Mục Tiêu Demo

Chứng minh TapLinkX3 có thể:
- Hiểu ý định giọng nói tự nhiên theo thời gian thực
- Điều hướng đúng đích mà không cần thao tác tay
- Giữ ngữ cảnh hội thoại một cách thông minh
- Xử lý an toàn khi câu lệnh mơ hồ

## Checklist Trước Khi Lên Sân Khấu (2-3 phút)

- Đảm bảo app đã kết nối mạng và bật voice input
- Xác nhận có ít nhất 3 dashboard trong thư viện
- Xác nhận có ít nhất 3 agent trong danh sách agent
- Chuẩn bị sẵn 1 cuộc hội thoại đang mở để demo chuyển ngữ cảnh
- Chuẩn bị 1 câu lệnh cố tình mơ hồ để demo fallback an toàn

---

## Kịch Bản 1: "Nhảy Thẳng Vào Dashboard Lãnh Đạo"

**Wow moment:** chỉ một câu nói mở đúng trang chi tiết dashboard mong muốn.

- **Điều kiện trước:** app đang ở home, danh sách dashboard, hoặc trang bất kỳ không phải dashboard detail
- **Câu nói chính (user):** "Mở dashboard doanh thu quý."
- **Câu nói thay thế:**
  - "Cho tôi vào dashboard sales Q2."
  - "Mở dashboard hiệu suất bán hàng lãnh đạo."
- **Expected routing:** `NAVIGATE_DASHBOARD_DETAIL`
- **Expected target:** `DASHBOARD_DETAIL` với `dashboard_id` khớp
- **Khán giả nên thấy:**
  - Transcript giọng nói xuất hiện
  - App chuyển thẳng đến đúng trang dashboard detail
  - Tiêu đề dashboard trên UI khớp với ý định vừa nói
- **Lời dẫn MC (sau khi chuyển trang):** "Không cần click. Hệ thống hiểu ý định và mở đúng dashboard."
- **Câu backup khi nhận diện nhiễu:** "Mở dashboard: Doanh thu quý."

**Vì sao ấn tượng:** chứng minh nhận diện thực thể chính xác, không chỉ điều hướng chung chung.

---

## Kịch Bản 2: "Từ Màn Hình Tổng Quan Sang Đúng Chuyên Gia Chỉ Bằng Giọng Nói"

**Wow moment:** user gọi đúng chuyên gia và app mở thẳng hồ sơ agent tương ứng.

- **Điều kiện trước:** app chưa ở trang chi tiết agent mục tiêu
- **Câu nói chính (user):** "Mở agent phân tích rủi ro."
- **Câu nói thay thế:**
  - "Vào hồ sơ agent compliance."
  - "Mở agent chiến lược mua sắm."
- **Expected routing:** `NAVIGATE_AGENT_DETAIL`
- **Expected target:** `AGENT_DETAIL` với `agent_id` khớp
- **Khán giả nên thấy:**
  - Assistant map tên agent từ danh sách agent hiện có
  - App mở trực tiếp trang chi tiết agent
  - Không cần cuộn tìm thủ công trong danh sách
- **Lời dẫn MC:** "Hệ thống định tuyến thẳng đến chuyên gia, không phải chỉ mở trang tìm kiếm."
- **Nếu có nhiều agent tên gần giống:** "Mở Risk Analysis agent trong thư viện Operations."

**Vì sao ấn tượng:** thể hiện khả năng nhận diện agent và ánh xạ ý định -> đúng chuyên gia.

---

## Kịch Bản 3: "Tiếp Tục Hội Thoại Đúng Ngữ Cảnh"

**Wow moment:** đang chat dở, user nói tiếp và hệ thống hiểu là tiếp tục thread hiện tại.

- **Điều kiện trước:** user đang ở trong một cuộc hội thoại hiện hữu
- **Câu nói chính (user):** "Tóm tắt nội dung này thành 3 ý cho ban lãnh đạo."
- **Câu nói thay thế:**
  - "Giờ chuyển thành email draft giúp tôi."
  - "Rút gọn còn bản cập nhật một phút."
- **Expected routing:** `CHAT_IN_CURRENT_CONVERSATION`
- **Expected target:** `NONE` (giữ nguyên cuộc hội thoại hiện tại)
- **Khán giả nên thấy:**
  - Vẫn ở cùng một thread chat
  - Câu trả lời có tham chiếu ngữ cảnh trước đó
  - Không tạo cuộc hội thoại mới, không nhảy trang
- **Lời dẫn MC:** "Hệ thống hiểu đây là lệnh tiếp nối, không phải bắt đầu lại từ đầu."
- **Mẹo trình diễn:** chỉ nhanh vào lịch sử chat trước khi nói để khán giả thấy ngữ cảnh.

**Vì sao ấn tượng:** cho thấy nhận thức ngữ cảnh phiên làm việc, không mất mạch hội thoại.

---

## Kịch Bản 4: "Đổi Agent Trực Tiếp Trong Lúc Đang Trao Đổi"

**Wow moment:** giữa cuộc trò chuyện, user đổi chuyên gia và hệ thống pivot ngay.

- **Điều kiện trước:** user đang chat với Agent A
- **Câu nói chính (user):** "Chuyển sang agent compliance và viết lại nội dung này theo góc nhìn policy review."
- **Câu nói thay thế:**
  - "Chuyển sang legal review agent và thêm phần rủi ro."
  - "Đổi qua finance agent và tối ưu giả định chi phí."
- **Expected routing:** `CHAT_WITH_AGENT_SWITCH`
- **Expected target:** ngữ cảnh chat tiếp tục với `agent_id` mới
- **Khán giả nên thấy:**
  - Agent context đổi từ Agent A sang Agent B
  - Cuộc hội thoại tiếp diễn, không mất nội dung trước đó
  - Câu trả lời mới phản ánh chuyên môn của agent vừa chuyển
- **Lời dẫn MC:** "Đây là phối hợp đa agent theo thời gian thực bằng giọng nói."
- **Nếu UI chưa thể hiện rõ việc chuyển:** nói thêm một câu ngắn gọi đúng tên agent mới.

**Vì sao ấn tượng:** thể hiện điều phối đa agent, vượt xa mô hình chatbot đơn lẻ.

---

## Kịch Bản 5: "Ghi Nội Dung Rảnh Tay Bằng Dictation"

**Wow moment:** nội dung công việc được đọc ra và chuyển thành text có thể chỉnh sửa ngay.

- **Điều kiện trước:** con trỏ đang focus vào ô nhập liệu hoặc vùng editor
- **Câu nói chính (user):** "Dictate: Việc cần làm. Chốt proposal trước thứ Sáu. Lên lịch gọi đối tác thứ Hai lúc 10 giờ."
- **Câu nói thay thế:**
  - "Dictate ghi chú họp: duyệt phase một, gỡ blocker QA, cập nhật ngày launch."
  - "Dictate tóm tắt: khách hàng muốn onboarding nhanh hơn và xuất analytics."
- **Expected routing:** `DICTATE_TEXT`
- **Expected target:** `NONE` (bơm text trực tiếp vào vùng nhập hiện tại)
- **Khán giả nên thấy:**
  - Speech được chuyển thành text ngay hoặc gần thời gian thực
  - Dấu câu và ngắt câu đủ dễ đọc
  - Nội dung có thể chỉnh sửa tức thì để hoàn thiện
- **Lời dẫn MC:** "Giọng nói trở thành đầu ra công việc có cấu trúc ngay lập tức."
- **Câu fallback nếu dấu câu chưa tốt:** "Dictate có dấu câu: Việc cần làm, hai chấm..."

**Vì sao ấn tượng:** chứng minh tăng tốc năng suất, không chỉ dừng ở điều hướng.

---

## Kịch Bản 6: "Phục Hồi An Toàn Khi Câu Lệnh Mơ Hồ"

**Wow moment:** câu lệnh mơ hồ không làm app đi sai; hệ thống fallback an toàn.

- **Điều kiện trước:** không có thực thể đủ rõ để map chính xác
- **Câu nói chính (user):** "Mở cái lúc nãy mình xem ấy... cái nào cũng được."
- **Câu nói thay thế:**
  - "Vào agent đó đi... tôi quên tên rồi."
  - "Mở dashboard tụi mình nói hôm qua."
- **Expected routing:** `NO_OP` (hoặc fallback do confidence thấp)
- **Expected target:** `NONE`
- **Khán giả nên thấy:**
  - App không điều hướng sai đích
  - User nhận prompt yêu cầu làm rõ hoặc xử lý an toàn
  - Màn hình hiện tại giữ ổn định
- **Lời dẫn MC:** "Khi độ tin cậy thấp, hệ thống ưu tiên an toàn thay vì đoán bừa."
- **Lệnh phục hồi để tiếp tục demo:** "Mở dashboard doanh thu quý."

**Vì sao ấn tượng:** thể hiện mức độ tin cậy và kiểm soát rủi ro chuẩn doanh nghiệp.

---

## Kịch Bản Bonus (Nếu Còn Thời Gian)

### Bonus A: "Mở Danh Sách Dashboard Bằng Lệnh Tổng Quát"
- **User nói:** "Hiển thị tất cả dashboard."
- **Expected routing:** `NAVIGATE_DASHBOARD_LIST`
- **Wow angle:** mở nhanh màn hình tổng quan trước khi drill-down.

### Bonus B: "Tạo Cuộc Hội Thoại Mới Ngay Lập Tức"
- **User nói:** "Bắt đầu hội thoại mới về mức độ sẵn sàng launch."
- **Expected routing:** `NAVIGATE_CONVERSATION_NEW`
- **Wow angle:** reset tác vụ nhanh, không cần thao tác menu.

---

## Timeline Gợi Ý Cho Demo 8 Phút

- **00:00-00:45** Mở màn + cam kết (điều hướng thông minh bằng giọng nói)
- **00:45-02:00** Kịch bản 1 (độ chính xác dashboard)
- **02:00-03:10** Kịch bản 2 (độ chính xác agent)
- **03:10-04:20** Kịch bản 3 (duy trì ngữ cảnh chat)
- **04:20-05:30** Kịch bản 4 (chuyển agent thời gian thực)
- **05:30-06:30** Kịch bản 5 (dictation tăng năng suất)
- **06:30-07:30** Kịch bản 6 (fallback an toàn)
- **07:30-08:00** Kết thúc (nhanh + thông minh + đáng tin)

## Ghi Chú Cho Người Trình Bày

- Nói câu lệnh ngắn, tự nhiên, rõ ràng
- Dừng khoảng nửa giây sau mỗi câu để khán giả quan sát chuyển trạng thái
- Luôn diễn giải bằng ngôn ngữ giá trị kinh doanh ("ra quyết định nhanh hơn", "ít ma sát thao tác", "giảm click sai")
- Kết luận bằng yếu tố tin cậy và guardrail, không chỉ nói về tốc độ
