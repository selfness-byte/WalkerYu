# WalkerYu (워커유) — 경기·수도권 실시간 대중교통 지도 & 홈 위젯

**WalkerYu**는 경기도 시내·광역버스와 수도권 지하철의 실시간 도착 정보를 지도와 홈 화면 위젯에서 한눈에 확인할 수 있는 Android 앱입니다.

## 주요 기능
- **실시간 대중교통 지도**: 내 주변 및 원하는 위치의 버스 정류장·지하철역 탐색 (`📡 이 위치 대중교통 찾기`)
- **원탭 노선 추가 (`★ 노선 추가`)**: 자주 타는 노선을 터치 한 번으로 위젯에 등록하고, 다른 지역을 탐색해도 지도 위에 별표(`★`) 마커 상시 유지
- **가독성 최적화 홈 위젯 (작게/보통/크게)**:
  - 정류장·역별 그룹 카드 및 개별 노선 다크 서브카드
  - **위치 ON 1분 주기 갱신**: 내 위치와 가까운 정류장(350m 이내)일 때 **화이트 네온 테두리 빠른 점멸 효과**
- **메모리 최적화 및 안정화**:
  - 마커 아이콘 `LruCache` 재사용 및 `osmdroid` 타일 메모리·디스크 캐시 상한 제어
  - 위젯 동시 갱신 방지(`Mutex`) 및 화면 종료 시 위치·코루틴 리소스 즉시 해제
- **자동 업데이트 지원**:
  - 앱 실행 시 및 설정 화면(`🔄 최신 업데이트 확인 및 자동 설치`)에서 `www.inanimals.co.kr` / GitHub 최신 APK 확인 후 SHA-256 무결성 검증 및 설치

## 다운로드 및 업데이트
- **공식 다운로드 페이지**: [https://www.inanimals.co.kr/walkeryu/](https://www.inanimals.co.kr/walkeryu/)
- **앱 포털**: [https://inanimals-1977.web.app/walkeryu/](https://inanimals-1977.web.app/walkeryu/)
- **최신 APK**: [`dist/WalkerYu-1.0.0.apk`](https://raw.githubusercontent.com/selfness-byte/WalkerYu/main/dist/WalkerYu-1.0.0.apk)
