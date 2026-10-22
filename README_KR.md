# 경기교통지도 (Android) — 내 주변 경기버스·지하철 지도 + 홈 위젯

프로젝트 위치: `/Volumes/M/Android_Transit_Map_Widget_20261022`

## 기능
| 구분 | 내용 |
|---|---|
| 지도 | OpenStreetMap(osmdroid, 키 불필요). 현재 위치(사람 아이콘) / 버스정류장(초록 원 `B`) / 지하철역(파란 사각 `역`) 구분 |
| 필터 | 🚌 버스 · 🚇 지하철 · 🚆 열차 위치 칩 |
| 하단 카드 | 정류장/역 탭 → 노선·방향별 도착 정보, 정류소번호(같은 이름 반대편 정류장 구분), 상행/하행·행선지 |
| 버스 노선 | 도착 행의 `노선·차량 위치` → 노선 경로 + 운행 중 차량(정류장 단위) 표시 |
| 열차 위치 | 지하철역 선택 + `열차 위치` 칩 → 그 역 호선들의 열차 위치(서울 실시간 열차 위치 API) |
| 위치 권한 거부 시 | 이름 검색, 지도 길게 누르기, `이 지역 검색` 버튼으로 선택 |
| 고정 목적지 | 목적지(예: 회사)마다 ‘탈 곳’(정류장+노선+방향 / 역+호선+방향)을 모아 저장 |
| 위젯 3가지 창 모드 | **작게**(탈 곳 1개) · **보통**(3개) · **크게**(최대 8개 + 방향·다음 도착) |
| 위젯 📍 버튼 | 위치 갱신 **ON**: 현재 위치에서 가까운 탈 곳 순으로 정렬·거리 표시 (목적지 없으면 내 주변 자동) / **OFF**: 위치 요청 없이 저장 순서 |
| 위젯 ⟳ 버튼 | 즉시 갱신. 자동 갱신은 Android 제한으로 약 15분 간격 |
| 위젯 탭 | 해당 정류장/역 카드로 앱 진입 |

상태 구분: `정보 없음`(운행 전·종료 또는 미제공) / `⚠ 통신 오류` / `⚠ API 키 확인 필요` / `(캐시)` 직전 성공값(30분 이내, 기준 시각 표시) / `[샘플]`.
조회 실패를 '곧 도착'이나 '운행 종료'로 바꿔 표시하지 않습니다.

## 사용 API
| 데이터 | 제공처 | 엔드포인트 |
|---|---|---|
| 주변 정류소·검색 | 경기도 버스정보 v2 (공공데이터포털) | `apis.data.go.kr/6410000/busstationservice/v2/getBusStationAroundListv2`, `getBusStationListv2` |
| 버스 도착 | 〃 | `busarrivalservice/v2/getBusArrivalListv2` |
| 노선 정류소 / 버스 위치 | 〃 | `busrouteservice/v2/getBusRouteStationListv2`, `buslocationservice/v2/getBusLocationListv2` |
| 지하철 실시간 도착 | 서울 열린데이터광장 | `swopenapi.seoul.go.kr/api/subway/{KEY}/json/realtimeStationArrival/0/40/{역명}` |
| 지하철 열차 위치 | 서울 열린데이터광장 | `…/realtimePosition/0/100/{호선명}` |
| 지하철역 좌표 | 앱 내장 `assets/stations.json` (OSM + Wikidata, 748곳, `tools/make_stations.py`로 생성). 내장 데이터가 없을 때만 Overpass 사용 | — |

> 공공데이터포털에서 경기버스 4개 서비스(정류소 조회, 버스도착정보 조회, 버스노선 조회, 버스위치정보 조회) 각각 **활용신청**이 되어 있어야 합니다.
> 서울 지하철 키를 비워 두면 공식 `sample` 키(실데이터, 최대 5건)를 씁니다.

## 빌드
```zsh
zsh /Volumes/M/Android_Transit_Map_Widget_20261022/scripts/build.sh            # APK 생성 → dist/
zsh /Volumes/M/Android_Transit_Map_Widget_20261022/scripts/build.sh --install  # USB 연결 기기에 설치
```
- JDK17·Android SDK·Gradle: `/Volumes/M/DevTools` (env.sh)
- Gradle 캐시·빌드 산출물: M 드라이브 안 APFS 이미지 `/Volumes/MDev` (exFAT `._*` 문제 회피)

## 처음 사용 순서
1. APK 설치 → 앱 실행 → 위치 권한 허용(거부해도 검색으로 사용 가능)
2. ⚙ 설정 → 경기버스 키 붙여넣기(Encoding/Decoding 아무거나) → (선택) 서울 지하철 키 → 저장
3. 지도에서 정류장/역 탭 → 원하는 노선·방향의 `★ 목적지에 추가` → 목적지 이름(예: 회사) 입력
4. 홈 화면 길게 누르기 → 위젯 → `경기교통지도` → 작게/보통/크게 중 선택해 배치
5. 위젯 📍 로 위치 갱신 ON/OFF. ON 상태로 앱이 닫혀 있어도 쓰려면 위치 권한을 ‘항상 허용’

## 개인정보
- 위치는 필요할 때만 1회 조회하고 저장·전송하지 않습니다(조회 좌표는 각 API 요청에만 사용).
- API 키는 기기 내부 SharedPreferences 에만 저장하며, 오류 메시지·로그에 URL/키를 넣지 않습니다.
