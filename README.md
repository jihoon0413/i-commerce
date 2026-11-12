# 🛒 I-commerce Backend (Payment Engine)

> **쿠팡 서비스 트래픽의 1%(MAU 33만 / DAU 15만) 규모를 역산하여, 결제 데이터 정합성과 외부 연동 장애 격리를 검증한 백엔드 시스템입니다.**
>
> - **진행 기간**: 2026.05 ~ 2026.06 (2개월)
> - **팀 구성 & 역할**: 백엔드 5인 (팀장 / 결제 도메인 설계 및 부하 테스트 전담)
> - **핵심 기술**: Java 25, Spring Boot, PostgreSQL, HikariCP, WireMock, AWS EC2
> - **핵심 검증**: 외부 PG 장애 격리(Facade), 보상 트랜잭션 기반 정합성 보장, 부하 테스트를 통한 재시도 한계 지표(p95 8초) 도출

---

# 🛠️ 핵심 문제 해결 및 기술 검증 (Core Engineering & Troubleshooting)

## 1. PG 승인 이후 장애 복구를 위한 결제 정합성 설계 (망 취소 / Saga)

### [문제 현상 및 배경]
- PG 승인은 완료되었으나 내부 DB 반영(재고 차감)에 실패하거나 네트워크 타임아웃 발생 시, **"고객 계좌에서는 돈이 출금되었으나 주문은 실패하는"** 치명적인 데이터 불일치 위험 존재.
- 동일 주문 건에 대한 네트워크 중복 요청 시 이중 승인 위험 노출.

### [해결 전략 및 아키텍처]
1. **상태 선점 기반 중복 결제 방어**:
   - 요청 진입 즉시 주문 상태를 `PROCESSING`으로 선점하여 동시성 인입 요청을 `PAYMENT_ALREADY_PROCESSING`으로 즉시 거절.
   <br>
   <img width="266" height="324" alt="image" src="https://github.com/user-attachments/assets/d0e0f851-f70b-408c-b1f4-38ac0b9c5356" />

2. **선결제-후재고 차감 및 Saga 보상 트랜잭션**:
   - 가용 재고 점유 이탈을 방지하기 위해 **결제 승인 -> 재고 차감** 순서 채택.
   - PG 승인 후 재고 차감 실패 시, `autoCancelPayment()`(망 취소)를 즉시 실행하여 승인을 자동 롤백.
   <br>
   <img width="1118" height="216" alt="image" src="https://github.com/user-attachments/assets/1d4703ca-bd17-4175-89d8-65c86d3d4ed0" />


3. **일시 오류 대응을 위한 재시도(Retry) 및 Fallback 조회 설계**:
   - **3회 재시도**: 일시적 통신 오류 방어를 위해 `@Retryable`(최대 3회)을 적용하여 성공률 방어.
   - **Fallback 단건 조회**: 3회 재시도 실패 시 Fallback 메서드에서 PG사 결제 단건 조회 API를 호출하여 실제 승인(`DONE`) 여부를 최종 확인.
   - 승인 완료 건은 정상 처리(`PAID`)하고, 미승인/통신 불가 건은 `UNKNOWN_HOLD`로 안전하게 격리
   <br>
   <img width="576" height="354" alt="image" src="https://github.com/user-attachments/assets/5361f282-f06e-4a3c-96a6-0fcc97917a7e" />
   
4. **`UNKNOWN_HOLD` 격리 및 망 취소 사후 복구 스케줄러**:
   - 응답 타임아웃 시 섣불리 실패 확정하지 않고 `UNKNOWN_HOLD` 상태로 격리.
   - 5분 주기 스케줄러가 PG 상태를 재조회(Reconcile)하여 `PAID` 또는 `CANCELED`로 최종 일관성(Eventual Consistency) 확보.
     
   - **타임아웃 격리**: 승인/취소 API 호출 중 네트워크 타임아웃 발생 시 섣불리 실패 처리하지 않고, 상태를 각각 `UNKNOWN_HOLD` / `CANCEL_UNKNOWN_HOLD`로 격리.
   - **망 취소 및 최종 일관성 확보**: 5분 주기 복구 스케줄러가 PG 상태를 재조회(Reconcile)하여 승인 여부를 확인한 뒤, 불일치 시 **망 취소를 수행**하거나 정상 처리하여 `PAID` 또는 `CANCELED`로 최종 일관성 확보.
   <br>
   <img width="860" height="334" alt="image" src="https://github.com/user-attachments/assets/fddac183-97b5-4f46-b68d-03132351aa92" />

### [상태 전이 흐름 (State Machine)]
<img width="1391" height="614" alt="image" src="https://github.com/user-attachments/assets/87608fcf-6935-4d40-a054-8de036aeb2a6" />


### [검증 결과]
| 검증 시나리오 | 확인한 동작 및 검증 내용 | 결과 |
| :--- | :--- | :---: |
| **승인 요청 재시도 후 성공** | 앞선 2회 실패 후 3번째 요청 성공 (PG API 총 3회 호출 확인) | **통과** |
| **3회 실패 후 조회 결과 `DONE`** | Fallback에서 PG 상태 조회 후 정상 승인(`DONE`) 확인 및 주문 확정 처리 | **통과** |
| **조회 결과 `IN_PROGRESS`** | Fallback에서 타임아웃 오류 발생 $\rightarrow$ `UNKNOWN_HOLD` 격리 확인 | **통과** |
| **상태 조회 중 네트워크 오류** | Fallback에서 타임아웃 오류 발생 $\rightarrow$ `UNKNOWN_HOLD` 격리 확인 | **통과** |
| **승인 API의 400 (Client) 오류** | 복구 불가능한 요청 에러로 판단하여 불필요한 재시도 없이 1회 호출 후 즉시 실패 처리 | **통과** |


---


## 2. 외부 PG 지연 격리를 위한 트랜잭션 경계 분리 (Facade 패턴)
- **문제 현상**: 결제 비즈니스 로직과 외부 PG API 호출이 단일 `@Transactional` 내에 묶여 있어, PG 네트워크 지연(I/O Block) 동안 DB 커넥션을 반환하지 못하는 병목 발생.
- **원인 분석**: 외부 API 응답 대기 시간이 곧 HikariCP 커넥션 점유 시간으로 이어져, 동시 요청 증가 시 커넥션 풀(Pool Size: 45) 고갈 위험 노출.
- **해결 전략**:
  - `PaymentFacade` 계층을 도입하여 트랜잭션 경계 분리.
  - `주문 생성(DB 트랜잭션)` $\rightarrow$ `PG 결제 승인(트랜잭션 외부)` $\rightarrow$ `결제 완료 처리(DB 트랜잭션)` 구조로 재설계하여 순수 DB I/O 구간에서만 커넥션을 점유하도록 격리.
- **정량적 성과**:
  - WireMock 기반 지연 모사 환경에서 **목표 TPS 50 달성 시 HikariCP 커넥션 고갈 0건**, 정상 요청 에러율 0% 유지.

---

## 3. MSA 전환을 고려한 도메인 결합도 최소화 규칙
- **문제 현상**: 모놀리식 구조에서 타 도메인 엔티티를 JPA 연관관계(`@ManyToOne`)로 직접 참조할 경우 강한 결합이 발생하여 향후 서비스 분리 시 대규모 수정 불가피.
- **해결 전략**:
  - **도메인 간 ID 간접 참조 원칙**: 타 도메인 참조 시 객체 참조를 배제하고 고유 ID 값만 보관.
  - **공개 인터페이스 경유**: 타 도메인 데이터 접근 및 처리는 반드시 상대 도메인의 Service 레이어(공개 API)를 통해서만 호출하도록 제한.
- **성과**:
  - 모놀리식 환경 내에서 **도메인 간 직접 엔티티 참조 0건** 유지, 향후 마이크로서비스로 독립 배포 가능한 구조 확보.

---

# 📊 부하 테스트 및 한계 지표 분석 (Troubleshooting & Next Step)

- **테스트 환경**: AWS EC2, WireMock(PG 장애/지연 모사), HikariCP Max 45
- **측정 결과 및 병목 확인**:
  - 목표 TPS 50 환경에서 안정적인 트랜잭션 처리를 확인.
  - 그러나 `@Retryable`을 통한 단순 재시도 적용 시, 외부 PG 장애 상황에서 재시도 대기 시간이 누적되어 **p95 응답 지연이 최대 8초까지 상승하는 한계**를 확인.
- **인사이트 & Next Step**:
  - 성공률은 방어했으나 사용자 응답 지연이 심각해지는 문제를 인식하고, 후속 프로젝트에서 **결제 유형별 신뢰성 전략(Fast-Fail / Circuit Breaker) 및 Transactional Outbox 기반 비동기 접수 구조**로 개선하는 계기가 됨.




| **데이터 아키텍처** | [Draw.io / ERDCloud 스키마 구조도](https://www.erdcloud.com/d/itmzsXzjE3eRcfGoy)                                                                                                                                             |
| **API 명세서** | [Swagger API 정적 문서 주소](http://localhost:8080/swagger-ui/index.html?urls.primaryName=Member)  
