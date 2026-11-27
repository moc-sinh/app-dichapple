package vn.mocsinh.mstrans.core.screen

/**
 * Words of the trades sold on Taobao and 1688, as Vietnamese shops say them (reviewed 26/11/2026).
 * Used for a whole line, and for a line made only of such words (a variant's name, a row of
 * specifications), which is then put together from them without the offline translator. Not put
 * into longer sentences: the offline translator loses words put in for it.
 */
object IndustryTerms {
    /** What a product is described by. */
    private val ATTRIBUTES = linkedMapOf(
        "颜色分类" to "Phân loại màu", "适用人群" to "Đối tượng sử dụng", "适用场景" to "Dùng cho", "适用年龄" to "Độ tuổi phù hợp",
        "适用季节" to "Mùa phù hợp", "适用对象" to "Đối tượng sử dụng", "风格" to "Phong cách", "款式" to "Kiểu dáng", "图案" to "Họa tiết",
        "版型" to "Form dáng", "上市时间" to "Thời gian ra mắt", "货号" to "Mã hàng", "产品名称" to "Tên sản phẩm", "商品编号" to "Mã sản phẩm",
        "净含量" to "Khối lượng tịnh", "保质期" to "Hạn sử dụng", "生产日期" to "Ngày sản xuất", "包装" to "Đóng gói", "包装清单" to "Danh sách đóng gói",
        "配件" to "Phụ kiện", "套餐" to "Combo", "套餐类型" to "Loại combo", "官方标配" to "Tiêu chuẩn chính hãng", "标配" to "Tiêu chuẩn",
        "礼盒装" to "Hộp quà", "单件" to "Một chiếc", "两件套" to "Bộ 2 món", "三件套" to "Bộ 3 món", "四件套" to "Bộ 4 món", "五件套" to "Bộ 5 món",
        "均码" to "Freesize", "加大" to "Ngoại cỡ", "加大码" to "Size lớn", "加厚" to "Dày dặn", "加长" to "Dài hơn", "加绒" to "Lót nỉ",
        "新款" to "Mẫu mới", "爆款" to "Mẫu bán chạy", "同款" to "Cùng mẫu", "升级款" to "Bản nâng cấp", "基础款" to "Bản cơ bản",
        "定制款" to "Bản đặt riêng", "经典款" to "Mẫu cổ điển", "男款" to "Mẫu nam", "女款" to "Mẫu nữ", "男女同款" to "Unisex",
        "通用款" to "Loại dùng chung", "通用型" to "Loại đa năng", "通用" to "Dùng chung", "儿童" to "Trẻ em", "成人" to "Người lớn",
        "婴儿" to "Em bé", "老人" to "Người già", "学生" to "Học sinh", "情侣" to "Cặp đôi", "男士" to "Nam", "女士" to "Nữ", "男童" to "Bé trai",
        "女童" to "Bé gái", "中老年" to "Trung niên", "版本" to "Phiên bản", "容量" to "Dung tích", "选择" to "Chọn", "请选择" to "Vui lòng chọn",
        "规格型号" to "Quy cách", "产品参数" to "Thông số sản phẩm", "商品参数" to "Thông số sản phẩm", "产品规格" to "Quy cách sản phẩm",
        "单品" to "Sản phẩm lẻ", "整套" to "Cả bộ", "一套" to "Một bộ", "一对" to "Một đôi", "一件" to "Một cái", "大号" to "Cỡ lớn",
        "中号" to "Cỡ vừa", "小号" to "Cỡ nhỏ", "迷你" to "Mini", "特大号" to "Cỡ rất lớn",
    )

    /** Colours. */
    private val COLOURS = linkedMapOf(
        "黑色" to "Đen", "白色" to "Trắng", "红色" to "Đỏ", "蓝色" to "Xanh dương", "绿色" to "Xanh lá", "黄色" to "Vàng", "灰色" to "Xám",
        "粉色" to "Hồng", "紫色" to "Tím", "橙色" to "Cam", "棕色" to "Nâu", "咖啡色" to "Nâu cà phê", "米色" to "Be", "米白" to "Trắng kem",
        "米白色" to "Trắng kem", "卡其" to "Kaki", "卡其色" to "Kaki", "杏色" to "Màu mơ", "驼色" to "Màu lạc đà", "藏青" to "Xanh than",
        "藏青色" to "Xanh than", "深蓝" to "Xanh đậm", "深蓝色" to "Xanh đậm", "浅蓝" to "Xanh nhạt", "浅蓝色" to "Xanh nhạt", "天蓝" to "Xanh da trời",
        "天蓝色" to "Xanh da trời", "湖蓝" to "Xanh hồ", "宝蓝" to "Xanh hoàng gia", "墨绿" to "Xanh rêu", "墨绿色" to "Xanh rêu",
        "军绿" to "Xanh lính", "军绿色" to "Xanh lính", "浅绿" to "Xanh lá nhạt", "酒红" to "Đỏ rượu vang", "酒红色" to "Đỏ rượu vang",
        "枣红" to "Đỏ mận", "玫红" to "Hồng đậm", "玫红色" to "Hồng đậm", "浅粉" to "Hồng nhạt", "深灰" to "Xám đậm", "深灰色" to "Xám đậm",
        "浅灰" to "Xám nhạt", "浅灰色" to "Xám nhạt", "银色" to "Bạc", "金色" to "Vàng kim", "玫瑰金" to "Vàng hồng", "香槟金" to "Vàng champagne",
        "香槟色" to "Màu champagne", "黑金" to "Đen vàng", "磨砂黑" to "Đen nhám", "哑光黑" to "Đen mờ", "亮黑" to "Đen bóng", "亮黑色" to "Đen bóng",
        "透明" to "Trong suốt", "透明色" to "Trong suốt", "玳瑁" to "Đồi mồi", "玳瑁色" to "Màu đồi mồi", "玳瑁金" to "Vàng đồi mồi",
        "彩色" to "Nhiều màu", "混色" to "Màu trộn", "随机色" to "Màu ngẫu nhiên", "随机" to "Ngẫu nhiên", "本色" to "Màu tự nhiên",
        "原木色" to "Màu gỗ tự nhiên", "象牙白" to "Trắng ngà", "奶白" to "Trắng sữa", "奶白色" to "Trắng sữa", "奶茶色" to "Màu trà sữa",
        "雾霾蓝" to "Xanh xám", "深空灰" to "Xám không gian", "枪色" to "Xám súng", "古铜色" to "Màu đồng cổ", "青色" to "Xanh ngọc",
        "肤色" to "Màu da", "裸色" to "Màu nude", "荧光绿" to "Xanh dạ quang", "白金" to "Trắng vàng", "黑银" to "Đen bạc", "黑框" to "Gọng đen",
    )

    /** What things are made of. */
    private val MATERIALS = linkedMapOf(
        "纯棉" to "Cotton 100%", "全棉" to "Cotton 100%", "棉质" to "Chất cotton", "涤纶" to "Polyester", "锦纶" to "Nylon", "尼龙" to "Nylon",
        "氨纶" to "Spandex", "真丝" to "Lụa tơ tằm", "丝绸" to "Lụa", "羊毛" to "Len lông cừu", "羊绒" to "Cashmere", "雪纺" to "Voan",
        "牛仔" to "Denim", "灯芯绒" to "Nhung tăm", "亚麻" to "Vải lanh", "棉麻" to "Cotton lanh", "皮革" to "Da", "真皮" to "Da thật",
        "牛皮" to "Da bò", "羊皮" to "Da cừu", "人造革" to "Da nhân tạo", "帆布" to "Vải canvas", "毛呢" to "Dạ", "针织" to "Dệt kim",
        "莫代尔" to "Modal", "冰丝" to "Lụa lạnh", "不锈钢" to "Thép không gỉ", "铝合金" to "Hợp kim nhôm", "钛合金" to "Hợp kim titan",
        "纯钛" to "Titan nguyên chất", "塑料" to "Nhựa", "硅胶" to "Silicon", "橡胶" to "Cao su", "玻璃" to "Thủy tinh", "钢化玻璃" to "Kính cường lực",
        "陶瓷" to "Gốm sứ", "实木" to "Gỗ tự nhiên", "竹子" to "Tre", "纸质" to "Giấy", "树脂" to "Nhựa resin", "亚克力" to "Acrylic",
        "碳纤维" to "Sợi carbon", "合金" to "Hợp kim", "黄铜" to "Đồng thau", "纯铜" to "Đồng nguyên chất", "镀金" to "Mạ vàng", "镀银" to "Mạ bạc",
        "板材" to "Nhựa acetate", "金属" to "Kim loại", "木质" to "Chất gỗ", "藤编" to "Đan mây", "环保" to "Thân thiện môi trường", "牛皮纸" to "Giấy kraft",
        "无纺布" to "Vải không dệt", "珍珠棉" to "Mút xốp PE", "海绵" to "Mút", "记忆棉" to "Mút hoạt tính", "乳胶" to "Cao su non",
    )

    /** Glasses: the variants that came out as "frame [colour Jing Note]". */
    private val GLASSES = linkedMapOf(
        "镜框" to "Gọng kính", "镜架" to "Gọng kính", "镜片" to "Tròng kính", "眼镜" to "Kính mắt", "近视" to "Cận thị", "远视" to "Viễn thị",
        "老花" to "Lão thị", "老花镜" to "Kính lão", "散光" to "Loạn thị", "平光" to "Không độ", "非球面" to "Phi cầu", "球面" to "Mặt cầu",
        "防蓝光" to "Chống ánh sáng xanh", "蓝光" to "Ánh sáng xanh", "变色" to "Đổi màu", "偏光" to "Phân cực", "墨镜" to "Kính râm",
        "太阳镜" to "Kính mát", "折射率" to "Chiết suất", "度数" to "Số độ", "颜色请备注" to "Ghi chú màu", "请备注" to "Vui lòng ghi chú",
        "备注" to "Ghi chú", "配镜" to "Cắt kính", "防辐射" to "Chống bức xạ", "全框" to "Gọng nguyên", "半框" to "Nửa gọng", "无框" to "Không gọng",
        "鼻托" to "Đệm mũi", "镜腿" to "Càng kính", "眼镜盒" to "Hộp kính", "眼镜布" to "Khăn lau kính", "超轻" to "Siêu nhẹ",
    )

    /** Phones, computers and the like. */
    private val ELECTRONICS = linkedMapOf(
        "手机" to "Điện thoại", "电脑" to "Máy tính", "笔记本电脑" to "Laptop", "平板" to "Máy tính bảng", "平板电脑" to "Máy tính bảng",
        "耳机" to "Tai nghe", "蓝牙" to "Bluetooth", "蓝牙耳机" to "Tai nghe Bluetooth", "无线" to "Không dây", "有线" to "Có dây",
        "充电器" to "Củ sạc", "充电宝" to "Sạc dự phòng", "移动电源" to "Sạc dự phòng", "数据线" to "Cáp sạc", "充电线" to "Cáp sạc",
        "快充" to "Sạc nhanh", "闪充" to "Sạc nhanh", "电池" to "Pin", "电池容量" to "Dung lượng pin", "屏幕" to "Màn hình",
        "显示器" to "Màn hình máy tính", "键盘" to "Bàn phím", "鼠标" to "Chuột", "音箱" to "Loa", "摄像头" to "Camera", "运行内存" to "RAM",
        "机身内存" to "Bộ nhớ trong", "存储容量" to "Dung lượng lưu trữ", "处理器" to "Bộ xử lý", "分辨率" to "Độ phân giải", "续航" to "Thời lượng pin",
        "超长续航" to "Pin cực lâu", "降噪" to "Chống ồn", "主动降噪" to "Chống ồn chủ động", "防水" to "Chống nước", "防尘" to "Chống bụi",
        "智能" to "Thông minh", "遥控" to "Điều khiển từ xa", "遥控器" to "Điều khiển từ xa", "插头" to "Phích cắm", "插座" to "Ổ cắm",
        "电源" to "Nguồn điện", "适配器" to "Adapter", "转换器" to "Bộ chuyển đổi", "转接头" to "Đầu chuyển", "接口" to "Cổng kết nối",
        "手机壳" to "Ốp điện thoại", "保护壳" to "Ốp bảo vệ", "保护套" to "Bao bảo vệ", "钢化膜" to "Kính cường lực dán màn", "贴膜" to "Miếng dán màn hình",
        "支架" to "Giá đỡ", "全新" to "Mới 100%", "二手" to "Đã qua sử dụng", "国行" to "Bản nội địa Trung Quốc", "港版" to "Bản Hồng Kông",
        "官方" to "Chính hãng", "原装" to "Hàng zin", "兼容" to "Tương thích", "智能手表" to "Đồng hồ thông minh", "手环" to "Vòng đeo tay thông minh",
        "路由器" to "Bộ phát Wi-Fi", "硬盘" to "Ổ cứng", "固态硬盘" to "Ổ SSD", "内存卡" to "Thẻ nhớ", "U盘" to "USB", "麦克风" to "Micro",
        "游戏" to "Chơi game", "电竞" to "Gaming", "机械键盘" to "Bàn phím cơ", "氮化镓" to "GaN", "多口" to "Nhiều cổng", "单口" to "Một cổng",
        "数显" to "Hiển thị số", "彩屏" to "Màn hình màu", "触屏" to "Màn hình cảm ứng", "感应" to "Cảm ứng", "灯光" to "Đèn",
    )

    /** Clothes, shoes and bags. */
    private val CLOTHES = linkedMapOf(
        "连衣裙" to "Váy liền", "半身裙" to "Chân váy", "T恤" to "Áo thun", "短袖" to "Tay ngắn", "长袖" to "Tay dài", "衬衫" to "Áo sơ mi",
        "卫衣" to "Áo nỉ", "外套" to "Áo khoác", "夹克" to "Áo khoác", "羽绒服" to "Áo phao lông vũ", "棉服" to "Áo phao bông", "毛衣" to "Áo len",
        "针织衫" to "Áo dệt kim", "背心" to "Áo ba lỗ", "吊带" to "Áo hai dây", "打底衫" to "Áo mặc trong", "裤子" to "Quần", "牛仔裤" to "Quần jean",
        "短裤" to "Quần short", "阔腿裤" to "Quần ống rộng", "运动裤" to "Quần thể thao", "休闲裤" to "Quần thường ngày", "西装" to "Vest",
        "内衣" to "Đồ lót", "内裤" to "Quần lót", "文胸" to "Áo ngực", "睡衣" to "Đồ ngủ", "袜子" to "Tất", "帽子" to "Mũ", "围巾" to "Khăn quàng",
        "手套" to "Găng tay", "运动鞋" to "Giày thể thao", "板鞋" to "Giày sneaker", "帆布鞋" to "Giày vải", "皮鞋" to "Giày da",
        "高跟鞋" to "Giày cao gót", "凉鞋" to "Sandal", "拖鞋" to "Dép lê", "靴子" to "Bốt", "双肩包" to "Ba lô", "背包" to "Ba lô",
        "手提包" to "Túi xách tay", "单肩包" to "Túi đeo vai", "斜挎包" to "Túi đeo chéo", "钱包" to "Ví", "行李箱" to "Vali", "腰包" to "Túi đeo hông",
        "宽松" to "Rộng rãi", "修身" to "Ôm dáng", "显瘦" to "Tôn dáng", "百搭" to "Dễ phối đồ", "透气" to "Thoáng khí", "舒适" to "Thoải mái",
        "保暖" to "Giữ ấm", "防晒" to "Chống nắng", "速干" to "Khô nhanh", "弹力" to "Co giãn", "高腰" to "Cạp cao", "低腰" to "Cạp trễ",
        "圆领" to "Cổ tròn", "V领" to "Cổ chữ V", "翻领" to "Cổ bẻ", "连帽" to "Có mũ", "拉链" to "Khóa kéo", "纽扣" to "Cúc áo", "口袋" to "Túi",
        "码数" to "Cỡ", "尺码表" to "Bảng size", "胸围" to "Vòng ngực", "腰围" to "Vòng eo", "臀围" to "Vòng mông", "衣长" to "Dài áo",
        "裤长" to "Dài quần", "肩宽" to "Rộng vai", "袖长" to "Dài tay", "身高" to "Chiều cao", "体重" to "Cân nặng", "建议" to "Gợi ý",
        "偏大" to "Form rộng", "偏小" to "Form nhỏ", "标准码" to "Đúng size", "春季" to "Mùa xuân", "夏季" to "Mùa hè", "秋季" to "Mùa thu",
        "冬季" to "Mùa đông", "春秋" to "Xuân thu", "四季" to "Bốn mùa", "秋冬" to "Thu đông", "春夏" to "Xuân hè",
    )

    /** House, kitchen, furniture. */
    private val HOME = linkedMapOf(
        "厨房" to "Nhà bếp", "收纳" to "Đựng đồ", "收纳盒" to "Hộp đựng đồ", "收纳袋" to "Túi đựng đồ", "收纳箱" to "Thùng đựng đồ",
        "置物架" to "Kệ để đồ", "保温杯" to "Bình giữ nhiệt", "水杯" to "Cốc nước", "杯子" to "Cốc", "炒锅" to "Chảo xào", "不粘锅" to "Chảo chống dính",
        "电饭煲" to "Nồi cơm điện", "空气炸锅" to "Nồi chiên không dầu", "床单" to "Ga trải giường", "被套" to "Vỏ chăn", "枕头" to "Gối",
        "枕套" to "Vỏ gối", "毛巾" to "Khăn mặt", "浴巾" to "Khăn tắm", "垃圾桶" to "Thùng rác", "晾衣架" to "Giá phơi đồ", "衣架" to "Móc treo đồ",
        "窗帘" to "Rèm cửa", "地毯" to "Thảm", "台灯" to "Đèn bàn", "吸顶灯" to "Đèn ốp trần", "家用" to "Dùng trong nhà", "大容量" to "Dung tích lớn",
        "便携" to "Tiện mang theo", "便携式" to "Loại cầm tay", "折叠" to "Gấp gọn", "可折叠" to "Gấp gọn được", "一次性" to "Dùng một lần",
        "食品级" to "Đạt chuẩn thực phẩm", "沙发" to "Sofa", "椅子" to "Ghế", "桌子" to "Bàn", "书桌" to "Bàn học", "办公桌" to "Bàn làm việc",
        "办公椅" to "Ghế văn phòng", "床垫" to "Nệm", "衣柜" to "Tủ quần áo", "书架" to "Giá sách", "鞋柜" to "Tủ giày", "茶几" to "Bàn trà",
        "餐具" to "Bộ đồ ăn", "筷子" to "Đũa", "勺子" to "Thìa", "刀具" to "Bộ dao", "砧板" to "Thớt", "保鲜盒" to "Hộp bảo quản thực phẩm",
        "保鲜膜" to "Màng bọc thực phẩm", "纸巾" to "Khăn giấy", "洗衣液" to "Nước giặt", "清洁" to "Vệ sinh", "除螨" to "Diệt mạt",
    )

    /** Skin, hair, babies, toys, food. */
    private val PERSONAL = linkedMapOf(
        "护肤" to "Dưỡng da", "化妆品" to "Mỹ phẩm", "口红" to "Son môi", "面膜" to "Mặt nạ", "精华" to "Tinh chất", "精华液" to "Serum",
        "乳液" to "Sữa dưỡng", "面霜" to "Kem dưỡng", "洗面奶" to "Sữa rửa mặt", "防晒霜" to "Kem chống nắng", "粉底" to "Kem nền",
        "粉底液" to "Kem nền dạng lỏng", "眼影" to "Phấn mắt", "香水" to "Nước hoa", "洗发水" to "Dầu gội", "护发素" to "Dầu xả", "沐浴露" to "Sữa tắm",
        "牙膏" to "Kem đánh răng", "牙刷" to "Bàn chải đánh răng", "补水" to "Cấp ẩm", "保湿" to "Dưỡng ẩm", "美白" to "Làm trắng", "控油" to "Kiểm soát dầu",
        "敏感肌" to "Da nhạy cảm", "干皮" to "Da khô", "油皮" to "Da dầu", "奶瓶" to "Bình sữa", "尿不湿" to "Bỉm", "纸尿裤" to "Bỉm",
        "婴儿车" to "Xe đẩy em bé", "玩具" to "Đồ chơi", "积木" to "Đồ chơi xếp hình", "益智" to "Phát triển trí tuệ", "拼图" to "Tranh ghép",
        "毛绒玩具" to "Thú nhồi bông", "零食" to "Đồ ăn vặt", "坚果" to "Các loại hạt", "茶叶" to "Trà", "咖啡" to "Cà phê", "饮料" to "Đồ uống",
        "调料" to "Gia vị", "新鲜" to "Tươi", "进口" to "Nhập khẩu", "无糖" to "Không đường", "低脂" to "Ít béo", "有机" to "Hữu cơ",
        "礼盒" to "Hộp quà", "散装" to "Hàng xá", "袋装" to "Đóng túi", "盒装" to "Đóng hộp", "罐装" to "Đóng lon", "瓶装" to "Đóng chai",
        "箱装" to "Đóng thùng", "独立包装" to "Đóng gói riêng", "宠物" to "Thú cưng", "猫粮" to "Thức ăn cho mèo", "狗粮" to "Thức ăn cho chó",
    )

    /** Cars, tools, machines, packing, stationery. */
    private val TRADE = linkedMapOf(
        "汽车" to "Ô tô", "车载" to "Dùng trên ô tô", "摩托车" to "Xe máy", "电动车" to "Xe điện", "自行车" to "Xe đạp", "轮胎" to "Lốp xe",
        "工具" to "Dụng cụ", "工具箱" to "Hộp dụng cụ", "电钻" to "Máy khoan", "螺丝" to "Ốc vít", "螺丝刀" to "Tua vít", "扳手" to "Cờ lê",
        "胶带" to "Băng dính", "设备" to "Thiết bị", "电机" to "Mô tơ điện", "马达" to "Mô tơ", "功率" to "Công suất", "电压" to "Điện áp",
        "电流" to "Dòng điện", "转速" to "Tốc độ quay", "纸箱" to "Thùng carton", "纸袋" to "Túi giấy", "塑料袋" to "Túi nilon",
        "快递袋" to "Túi gói hàng", "气泡膜" to "Màng xốp hơi", "文具" to "Văn phòng phẩm", "钢笔" to "Bút máy", "中性笔" to "Bút gel",
        "铅笔" to "Bút chì", "打印机" to "Máy in", "工厂" to "Nhà máy", "工厂直销" to "Bán trực tiếp từ xưởng", "厂家直销" to "Bán trực tiếp từ xưởng",
        "批发价" to "Giá sỉ", "零售价" to "Giá lẻ", "起订" to "Đặt tối thiểu", "现货速发" to "Có sẵn, gửi nhanh", "支持定制" to "Nhận đặt làm riêng",
        "来图定制" to "Làm theo mẫu khách gửi", "包装盒" to "Hộp đóng gói", "说明书" to "Sách hướng dẫn", "保修" to "Bảo hành",
        "一年保修" to "Bảo hành 1 năm", "质保" to "Bảo hành",
    )

    /** Delivery, guarantees, and the "choose a variant" sheet. */
    private val SHOP = linkedMapOf(
        "已选" to "Đã chọn", "有货" to "Còn hàng", "无货" to "Hết hàng", "缺货" to "Hết hàng", "库存紧张" to "Sắp hết hàng", "购买数量" to "Số lượng mua",
        "限购" to "Giới hạn mua", "平台加补后" to "Sau trợ giá sàn", "平台补贴" to "Sàn trợ giá", "优惠前" to "Trước giảm giá", "券后价" to "Giá sau phiếu",
        "预计后天送达" to "Dự kiến giao ngày kia", "预计明天送达" to "Dự kiến giao ngày mai", "预计今天送达" to "Dự kiến giao hôm nay",
        "预计今日送达" to "Dự kiến giao hôm nay", "预计明日送达" to "Dự kiến giao ngày mai", "预计送达" to "Dự kiến giao", "送达" to "Giao tới",
        "发货地" to "Nơi gửi hàng", "发货时间" to "Thời gian gửi hàng", "顺丰" to "SF Express", "京东物流" to "JD Logistics", "极速退款" to "Hoàn tiền nhanh",
        "假一赔十" to "Hàng giả đền gấp 10", "退货包运费" to "Miễn phí trả hàng", "正品保障" to "Đảm bảo chính hãng", "极速发货" to "Gửi hàng nhanh",
        "当天发货" to "Gửi trong ngày", "次日达" to "Giao ngày hôm sau", "隔日达" to "Giao sau 1 ngày", "坏了包退" to "Hỏng được trả",
        "免费退换" to "Đổi trả miễn phí", "上门取件" to "Lấy hàng tận nơi", "货到付款" to "Thanh toán khi nhận hàng", "分期" to "Trả góp",
        "花呗分期" to "Trả góp Huabei", "加购" to "Thêm vào giỏ", "领取" to "Nhận", "立即领取" to "Nhận ngay", "去使用" to "Dùng ngay",
        "店铺优惠" to "Ưu đãi cửa hàng", "折扣" to "Giảm giá", "包装费" to "Phí đóng gói", "实付款" to "Thực trả",
        "合计" to "Tổng cộng", "收银台" to "Quầy thanh toán", "订单信息" to "Thông tin đơn hàng", "商品名称" to "Tên sản phẩm",
    )

    /** Scales and measuring (a list of scales on 1688 came out "Quy mô điện tử", 27/11/2026). */
    private val MEASURE = linkedMapOf(
        "秤" to "Cân", "电子秤" to "Cân điện tử", "电子称" to "Cân điện tử", "台秤" to "Cân bàn", "地磅" to "Cân sàn", "地秤" to "Cân sàn",
        "吊秤" to "Cân treo", "计价秤" to "Cân tính tiền", "计重秤" to "Cân trọng lượng", "计数秤" to "Cân đếm", "收银秤" to "Cân thu ngân",
        "条码秤" to "Cân in mã vạch", "厨房秤" to "Cân nhà bếp", "体重秤" to "Cân sức khỏe", "人体秤" to "Cân sức khỏe", "珠宝秤" to "Cân trang sức",
        "智能秤" to "Cân thông minh", "天平" to "Cân tiểu ly", "电子天平" to "Cân tiểu ly điện tử", "衡器" to "Thiết bị cân", "称重" to "Cân",
        "精密称重" to "Cân chính xác", "计数" to "Đếm số lượng", "计数功能" to "Chức năng đếm", "单位转换" to "Chuyển đổi đơn vị",
        "校准" to "Hiệu chuẩn", "简易校准" to "Hiệu chuẩn dễ", "精度" to "Độ chính xác", "高精度" to "Độ chính xác cao", "精度显示" to "Hiển thị độ chính xác",
        "量程" to "Phạm vi đo", "最大称量" to "Tải trọng tối đa", "分度值" to "Bước chia", "去皮" to "Trừ bì", "累计" to "Cộng dồn",
        "报警" to "Báo động", "重量报警" to "Báo động trọng lượng", "数量报警" to "Báo động số lượng", "报警灯" to "Đèn báo động",
        "三色报警灯" to "Đèn báo động 3 màu", "上下限" to "Ngưỡng trên dưới", "声光" to "Âm thanh và ánh sáng", "声光报警" to "Báo động âm thanh và ánh sáng",
        "港斤" to "Cân Hồng Kông", "港磅" to "Pound Hồng Kông", "台斤" to "Cân Đài Loan", "实验室" to "Phòng thí nghiệm", "商业" to "Thương mại",
        "工业" to "Công nghiệp", "工业级" to "Chuẩn công nghiệp", "测量" to "Đo lường", "卷尺" to "Thước cuộn", "游标卡尺" to "Thước kẹp",
        "温度计" to "Nhiệt kế", "湿度计" to "Ẩm kế", "万用表" to "Đồng hồ vạn năng", "测距仪" to "Máy đo khoảng cách", "水平仪" to "Thước thủy",
        "传感器" to "Cảm biến", "称重传感器" to "Cảm biến cân", "显示屏" to "Màn hình hiển thị", "液晶" to "LCD", "背光" to "Đèn nền",
        "充电款" to "Bản sạc pin", "插电款" to "Bản cắm điện", "电池款" to "Bản dùng pin", "碳钢" to "Thép carbon", "台面" to "Mặt bàn",
        "碳钢台面" to "Mặt bàn thép carbon", "不锈钢台面" to "Mặt bàn inox", "设计尺寸" to "Kích thước thiết kế", "秤盘" to "Đĩa cân",
        "耐用" to "Bền", "防水耐用" to "Chống nước, bền", "精准" to "Chính xác", "省电" to "Tiết kiệm điện", "精准省电" to "Chính xác, tiết kiệm điện",
        "英式" to "Kiểu Anh", "欧式" to "Kiểu châu Âu", "美式" to "Kiểu Mỹ", "英式插头" to "Phích cắm kiểu Anh", "专用" to "Chuyên dụng",
        "港澳" to "Hồng Kông, Ma Cao", "港澳专用" to "Dùng cho Hồng Kông, Ma Cao", "一斤十六两" to "1 cân 16 lạng",
    )

    /** What a 1688 supplier says of itself and of its service. */
    private val FACTORY = linkedMapOf(
        "源头厂家" to "Xưởng gốc", "源头" to "Tận gốc", "源头好货" to "Hàng tốt tận gốc", "厂家直供" to "Xưởng cung cấp trực tiếp",
        "实力商家" to "Nhà bán uy tín", "实力工厂" to "Xưởng uy tín", "超级工厂" to "Siêu xưởng", "深度验商" to "Đã kiểm định kỹ",
        "诚信通" to "Thành viên uy tín", "专业研发" to "Nghiên cứu phát triển chuyên nghiệp", "研发" to "Nghiên cứu phát triển",
        "可开发票" to "Có xuất hóa đơn", "开发票" to "Xuất hóa đơn", "专票" to "Hóa đơn VAT", "增票" to "Hóa đơn VAT", "普票" to "Hóa đơn thường",
        "品质保障" to "Đảm bảo chất lượng", "品质" to "Chất lượng", "质量" to "Chất lượng", "售后无忧" to "Hậu mãi yên tâm",
        "技术支持" to "Hỗ trợ kỹ thuật", "技术售后" to "Hậu mãi kỹ thuật", "专业技术售后" to "Hậu mãi kỹ thuật chuyên nghiệp",
        "支持二次开发" to "Hỗ trợ phát triển phần mềm riêng", "二次开发" to "Phát triển phần mềm riêng", "免费送软件" to "Tặng phần mềm",
        "送软件" to "Tặng phần mềm", "软件" to "Phần mềm", "免费" to "Miễn phí", "可选" to "Tùy chọn", "黑白" to "Đen trắng",
        "全兼容" to "Tương thích hoàn toàn", "算法" to "Thuật toán", "杜邦" to "Dupont", "连接" to "Kết nối", "蓝牙连接" to "Kết nối Bluetooth",
        "通讯" to "Truyền thông", "通信" to "Truyền thông", "串口" to "Cổng nối tiếp", "两种" to "2 loại", "三种" to "3 loại", "多种" to "Nhiều loại",
        "回头率" to "Tỷ lệ khách quay lại", "复购率" to "Tỷ lệ mua lại", "好评率" to "Tỷ lệ đánh giá tốt", "揽收率" to "Tỷ lệ lấy hàng",
        "跨境包邮" to "Miễn phí vận chuyển quốc tế", "跨境" to "Xuyên biên giới", "支持混批" to "Hỗ trợ nhập sỉ nhiều mẫu",
        "现货秒发" to "Có sẵn, gửi ngay", "包退换" to "Bao đổi trả", "工厂价" to "Giá xưởng", "出厂价" to "Giá xuất xưởng",
        "代工" to "Gia công", "贴牌" to "Gắn nhãn riêng", "来样定制" to "Làm theo mẫu", "打样" to "Làm mẫu", "量大从优" to "Mua nhiều giá tốt",
        "量大价优" to "Mua nhiều giá tốt", "热销" to "Bán chạy", "爆品" to "Sản phẩm hot", "精选" to "Tuyển chọn", "好评" to "Đánh giá tốt",
        "专卖店" to "Cửa hàng chuyên doanh", "专营店" to "Cửa hàng chuyên doanh", "企业店" to "Cửa hàng doanh nghiệp", "老店" to "Cửa hàng lâu năm",
        "超级立减" to "Siêu giảm ngay", "立减" to "Giảm ngay", "直降" to "Giảm thẳng", "百亿补贴" to "Trợ giá 10 tỷ", "官方补贴" to "Trợ giá chính hãng",
        "限时折扣" to "Giảm giá có hạn", "优惠" to "Ưu đãi", "种草" to "Gợi ý", "发货地" to "Nơi gửi hàng", "保障" to "Bảo đảm",
    )

    /** Machines, electrical goods, hardware, building. */
    private val MACHINES = linkedMapOf(
        "电线" to "Dây điện", "电缆" to "Cáp điện", "开关" to "Công tắc", "灯泡" to "Bóng đèn", "灯带" to "Dải đèn LED", "LED灯" to "Đèn LED",
        "太阳能" to "Năng lượng mặt trời", "逆变器" to "Bộ biến tần", "变压器" to "Máy biến áp", "发电机" to "Máy phát điện", "水泵" to "Máy bơm nước",
        "风扇" to "Quạt", "电风扇" to "Quạt điện", "空调" to "Điều hòa", "冰箱" to "Tủ lạnh", "洗衣机" to "Máy giặt", "吸尘器" to "Máy hút bụi",
        "电熨斗" to "Bàn là", "电热水壶" to "Ấm siêu tốc", "包装机" to "Máy đóng gói", "封口机" to "Máy hàn miệng túi", "打包机" to "Máy đóng đai",
        "切割机" to "Máy cắt", "电焊机" to "Máy hàn điện", "焊机" to "Máy hàn", "缝纫机" to "Máy may", "注塑机" to "Máy ép nhựa",
        "雕刻机" to "Máy khắc", "激光" to "Laser", "标签机" to "Máy in tem nhãn", "扫码枪" to "Máy quét mã vạch", "收银机" to "Máy tính tiền",
        "监控" to "Camera giám sát", "监控摄像头" to "Camera giám sát", "门锁" to "Khóa cửa", "智能门锁" to "Khóa cửa thông minh",
        "零件" to "Linh kiện", "汽车配件" to "Phụ tùng ô tô", "轴承" to "Vòng bi", "齿轮" to "Bánh răng", "阀门" to "Van", "管件" to "Phụ kiện ống",
        "水管" to "Ống nước", "五金" to "Ngũ kim", "五金工具" to "Dụng cụ ngũ kim", "建材" to "Vật liệu xây dựng", "瓷砖" to "Gạch men",
        "地板" to "Sàn nhà", "油漆" to "Sơn", "胶水" to "Keo dán", "包装材料" to "Vật liệu đóng gói", "标签纸" to "Giấy in tem", "热敏纸" to "Giấy in nhiệt",
    )

    /** Jewellery, fabric, decoration, outdoors. */
    private val MORE = linkedMapOf(
        "首饰" to "Trang sức", "饰品" to "Phụ kiện trang sức", "项链" to "Dây chuyền", "耳环" to "Bông tai", "耳钉" to "Khuyên tai", "戒指" to "Nhẫn",
        "手链" to "Lắc tay", "手镯" to "Vòng tay", "发饰" to "Phụ kiện tóc", "发夹" to "Kẹp tóc", "假发" to "Tóc giả", "美甲" to "Làm móng",
        "化妆刷" to "Cọ trang điểm", "布料" to "Vải", "面料" to "Vải", "辅料" to "Phụ liệu may", "花边" to "Ren", "蕾丝" to "Ren", "织带" to "Dây dệt",
        "口罩" to "Khẩu trang", "户外" to "Ngoài trời", "露营" to "Cắm trại", "帐篷" to "Lều", "钓鱼" to "Câu cá", "鱼竿" to "Cần câu",
        "健身" to "Thể hình", "瑜伽垫" to "Thảm yoga", "宠物用品" to "Đồ dùng thú cưng", "灯具" to "Đèn", "吊灯" to "Đèn chùm", "装饰" to "Trang trí",
        "摆件" to "Đồ trang trí", "相框" to "Khung ảnh", "花瓶" to "Bình hoa", "仿真花" to "Hoa giả", "人造花" to "Hoa giả", "圣诞" to "Giáng sinh",
        "派对" to "Tiệc", "气球" to "Bóng bay", "书包" to "Cặp sách", "母婴用品" to "Đồ mẹ và bé", "童装" to "Quần áo trẻ em", "鞋子" to "Giày",
        "包包" to "Túi xách", "手表" to "Đồng hồ", "眼镜框" to "Gọng kính",
    )

    /** Sports and games, lamps and lighting (a billiard table's and a lamp's variants, 27/11/2026). */
    private val SPORTS_LIGHTS = linkedMapOf(
        "台球" to "Bi-a", "台球桌" to "Bàn bi-a", "台球杆" to "Cơ bi-a", "球杆" to "Cơ bi-a", "台球厅" to "Quán bi-a", "球房" to "Phòng bi-a",
        "乒乓球" to "Bóng bàn", "乒乓球桌" to "Bàn bóng bàn", "羽毛球" to "Cầu lông", "篮球" to "Bóng rổ", "足球" to "Bóng đá", "网球" to "Quần vợt",
        "高尔夫" to "Golf", "跑步机" to "Máy chạy bộ", "哑铃" to "Tạ tay", "三合一" to "3 trong 1", "二合一" to "2 trong 1", "加大号" to "Cỡ lớn",
        "超大号" to "Cỡ siêu lớn", "高脚" to "Chân cao", "不折叠" to "Không gấp", "折叠款" to "Loại gấp", "级别" to "Cỡ", "即将售罄" to "Sắp hết hàng",
        "售罄" to "Hết hàng", "练习" to "Luyện tập", "训练" to "Tập luyện", "神器" to "Dụng cụ", "吸顶" to "Ốp trần", "吸顶灯" to "Đèn ốp trần",
        "吸/吊两用" to "Ốp trần hoặc treo", "吸吊两用" to "Ốp trần hoặc treo", "两用" to "Dùng được 2 cách", "胡桃木" to "Gỗ óc chó",
        "胡桃木色" to "Màu gỗ óc chó", "木纹" to "Vân gỗ", "木纹色" to "Màu vân gỗ", "圆角" to "Bo góc", "菱形" to "Hình thoi", "安装" to "Lắp đặt",
        "联系客服" to "Liên hệ CSKH", "免安装" to "Không cần lắp", "光源" to "Nguồn sáng", "照度" to "Độ rọi", "显色指数" to "Chỉ số hoàn màu",
        "护眼" to "Bảo vệ mắt", "无频闪" to "Không nhấp nháy", "调光" to "Chỉnh độ sáng", "色温" to "Nhiệt độ màu", "暖光" to "Ánh sáng vàng",
        "白光" to "Ánh sáng trắng", "中性光" to "Ánh sáng trung tính", "三色变光" to "Đổi 3 màu ánh sáng",
    )

    /** Places goods are sent from (the line under a product on 1688), and countries. */
    private val PLACES = linkedMapOf(
        "中国" to "Trung Quốc", "香港" to "Hồng Kông", "澳门" to "Ma Cao", "台湾" to "Đài Loan", "日本" to "Nhật Bản", "韩国" to "Hàn Quốc",
        "德国" to "Đức", "美国" to "Mỹ", "英国" to "Anh", "法国" to "Pháp", "意大利" to "Ý", "越南" to "Việt Nam", "泰国" to "Thái Lan",
        "广东" to "Quảng Đông", "浙江" to "Chiết Giang", "福建" to "Phúc Kiến", "江苏" to "Giang Tô", "山东" to "Sơn Đông", "河北" to "Hà Bắc",
        "河南" to "Hà Nam", "湖南" to "Hồ Nam", "湖北" to "Hồ Bắc", "安徽" to "An Huy", "江西" to "Giang Tây", "广西" to "Quảng Tây", "四川" to "Tứ Xuyên",
        "云南" to "Vân Nam", "上海" to "Thượng Hải", "北京" to "Bắc Kinh", "天津" to "Thiên Tân", "重庆" to "Trùng Khánh", "广州" to "Quảng Châu",
        "深圳" to "Thâm Quyến", "东莞" to "Đông Quan", "佛山" to "Phật Sơn", "中山" to "Trung Sơn", "汕头" to "Sán Đầu", "揭阳" to "Yết Dương",
        "潮州" to "Triều Châu", "江门" to "Giang Môn", "惠州" to "Huệ Châu", "珠海" to "Chu Hải", "义乌" to "Nghĩa Ô", "杭州" to "Hàng Châu",
        "宁波" to "Ninh Ba", "温州" to "Ôn Châu", "金华" to "Kim Hoa", "台州" to "Đài Châu", "湖州" to "Hồ Châu", "绍兴" to "Thiệu Hưng",
        "厦门" to "Hạ Môn", "泉州" to "Tuyền Châu", "莆田" to "Phủ Điền", "苏州" to "Tô Châu", "南京" to "Nam Kinh", "无锡" to "Vô Tích",
        "常州" to "Thường Châu", "青岛" to "Thanh Đảo", "临沂" to "Lâm Nghi", "石家庄" to "Thạch Gia Trang", "保定" to "Bảo Định", "成都" to "Thành Đô",
        "武汉" to "Vũ Hán", "长沙" to "Trường Sa", "郑州" to "Trịnh Châu", "西安" to "Tây An", "南宁" to "Nam Ninh", "昆明" to "Côn Minh",
    )

    /** Everything above; later groups win where two give the same word. */
    val ALL: Map<String, String> = ATTRIBUTES + COLOURS + MATERIALS + GLASSES + ELECTRONICS + CLOTHES + HOME + PERSONAL + TRADE + SHOP +
        MEASURE + FACTORY + MACHINES + MORE + SPORTS_LIGHTS + PLACES

    /** Shorter forms for places with little room (a price row, a chip). */
    val SHORT: Map<String, String> = linkedMapOf(
        "平台加补后" to "Sau trợ giá", "优惠前" to "Giá gốc", "预计后天送达" to "Giao ngày kia", "预计明天送达" to "Giao ngày mai",
        "预计今天送达" to "Giao hôm nay", "有货" to "Còn hàng", "颜色请备注" to "Ghi chú màu", "防蓝光" to "Chống AS xanh",
        "跨境包邮" to "Freeship QT", "专业技术售后" to "Hậu mãi kỹ thuật", "支持二次开发" to "Hỗ trợ tùy biến", "可开发票" to "Có hóa đơn",
        "品质保障" to "Đảm bảo CL", "源头厂家" to "Xưởng gốc", "三色报警灯" to "Đèn báo 3 màu", "声光报警" to "Báo động",
    )

    /**
     * Words naming the thing itself (a kind of goods): in Vietnamese they come first, the words
     * describing them after ("秋季新款" is "Mẫu mới mùa thu", "防蓝光镜片" "Tròng kính chống ánh sáng xanh").
     */
    val HEADS: Set<String> = (
        "连衣裙 半身裙 T恤 衬衫 卫衣 外套 夹克 羽绒服 棉服 毛衣 针织衫 背心 吊带 打底衫 裤子 牛仔裤 短裤 阔腿裤 运动裤 休闲裤 西装 " +
            "内衣 内裤 文胸 睡衣 袜子 帽子 围巾 手套 运动鞋 板鞋 帆布鞋 皮鞋 高跟鞋 凉鞋 拖鞋 靴子 双肩包 背包 手提包 单肩包 斜挎包 钱包 " +
            "行李箱 腰包 镜框 镜架 镜片 眼镜 老花镜 墨镜 太阳镜 眼镜盒 眼镜布 手机 电脑 笔记本电脑 平板 平板电脑 耳机 蓝牙耳机 充电器 " +
            "充电宝 移动电源 数据线 充电线 电池 显示器 键盘 鼠标 音箱 摄像头 遥控器 插头 插座 适配器 转换器 转接头 手机壳 保护壳 保护套 " +
            "钢化膜 支架 智能手表 手环 路由器 硬盘 固态硬盘 内存卡 U盘 麦克风 机械键盘 收纳盒 收纳袋 收纳箱 置物架 保温杯 水杯 杯子 炒锅 " +
            "不粘锅 电饭煲 空气炸锅 床单 被套 枕头 枕套 毛巾 浴巾 垃圾桶 晾衣架 衣架 窗帘 地毯 台灯 吸顶灯 沙发 椅子 桌子 书桌 办公桌 " +
            "办公椅 床垫 衣柜 书架 鞋柜 茶几 餐具 筷子 勺子 刀具 砧板 保鲜盒 保鲜膜 纸巾 洗衣液 化妆品 口红 面膜 精华 精华液 乳液 面霜 " +
            "洗面奶 防晒霜 粉底 粉底液 眼影 香水 洗发水 护发素 沐浴露 牙膏 牙刷 奶瓶 尿不湿 纸尿裤 婴儿车 玩具 积木 拼图 毛绒玩具 零食 " +
            "坚果 茶叶 咖啡 饮料 调料 猫粮 狗粮 礼盒 轮胎 工具 工具箱 电钻 螺丝 螺丝刀 扳手 胶带 设备 电机 马达 纸箱 纸袋 塑料袋 快递袋 " +
            "气泡膜 钢笔 中性笔 铅笔 打印机 包装盒 说明书 新款 爆款 同款 升级款 基础款 定制款 经典款 男款 女款 通用款 通用型 " +
            "秤 电子秤 电子称 台秤 地磅 地秤 吊秤 计价秤 计重秤 计数秤 收银秤 条码秤 厨房秤 体重秤 人体秤 珠宝秤 智能秤 天平 电子天平 衡器 " +
            "报警灯 三色报警灯 传感器 称重传感器 显示屏 软件 英式插头 卷尺 游标卡尺 温度计 湿度计 万用表 测距仪 水平仪 灯泡 灯带 LED灯 " +
            "逆变器 变压器 发电机 水泵 风扇 电风扇 空调 冰箱 洗衣机 吸尘器 电熨斗 电热水壶 包装机 封口机 打包机 切割机 电焊机 焊机 缝纫机 " +
            "注塑机 雕刻机 标签机 扫码枪 收银机 监控摄像头 门锁 智能门锁 轴承 齿轮 阀门 水管 瓷砖 胶水 标签纸 热敏纸 项链 耳环 耳钉 戒指 " +
            "手链 手镯 发夹 假发 化妆刷 口罩 帐篷 鱼竿 瑜伽垫 吊灯 摆件 相框 花瓶 仿真花 人造花 气球 书包 童装 鞋子 包包 手表 眼镜框"
        ).split(' ').toSet()

    /** Names kept with their capital inside a line. */
    val BORROWED: Set<String> = setOf("Bluetooth", "RAM", "USB", "GaN", "SF", "JD", "Alipay", "Taobao", "Tmall", "Wi-Fi", "Pinduoduo", "OEM")

    /** English words Vietnamese shops use as they are (small letters inside a line). */
    val LOANWORDS: Set<String> = setOf(
        "cotton", "polyester", "nylon", "spandex", "cashmere", "denim", "modal", "acrylic", "silicon", "adapter", "laptop", "camera",
        "gaming", "mini", "combo", "freesize", "unisex", "sofa", "serum", "flash", "sneaker", "sandal", "kraft", "canvas", "resin",
        "acetate", "carbon", "titan", "inox", "jean", "vest", "nude", "kaki", "champagne", "router", "micro", "remote", "voan", "jeans",
    )

    /** Left as they are: the grey square that says a variant has no picture. */
    val KEEP: Set<String> = setOf("无图", "暂无图片", "暂无图")

    /** One letter that joins words, for a line put together from the words above. */
    val GLUE: Map<Char, String> = mapOf('防' to "chống", '送' to "tặng", '与' to "và", '和' to "và", '及' to "và", '或' to "hoặc", '带' to "kèm", '含' to "gồm")

    /** A number and its unit: "500毫升" is "500 ml". Longer units first. */
    val UNITS: List<Pair<String, String>> = listOf(
        "厘米" to "cm", "毫米" to "mm", "毫升" to "ml", "毫安" to "mAh", "公斤" to "kg", "千克" to "kg", "英寸" to "inch", "个月" to "tháng",
        "小时" to "giờ", "分钟" to "phút", "度" to "độ", "件" to "cái", "个" to "cái", "只" to "chiếc", "双" to "đôi", "套" to "bộ", "包" to "gói",
        "盒" to "hộp", "箱" to "thùng", "瓶" to "chai", "斤" to "cân", "克" to "g", "升" to "lít", "米" to "m", "寸" to "inch", "瓦" to "W",
        "伏" to "V", "天" to "ngày", "年" to "năm", "岁" to "tuổi", "码" to "size", "色" to "màu", "款" to "mẫu", "片" to "miếng", "支" to "cây",
        "袋" to "túi", "台" to "chiếc", "张" to "tờ", "条" to "cái", "卷" to "cuộn", "把" to "cái",
    )
}
