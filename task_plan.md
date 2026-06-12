# Task Plan: hzx_common 分支分析 & B小车重叠修复

## Goal
拉取 `hzx_common` 分支，分析其改动（之前无重叠但有死锁），修复 B 小车重叠问题，编码并测试。

## Phases
- [x] Phase 1: 拉取 hzx_common 分支并了解仓库结构 ✅
- [x] Phase 2: 分析 hzx_common 的改动（diff），理解死锁问题 ✅
- [x] Phase 3: 分析 B 小车重叠问题的根因 ✅
- [x] Phase 4: 编码修复重叠 + 死锁问题 ✅
- [x] Phase 5: 测试验证 ✅ (16/16 纯单元通过)

## Status
**全部完成** ✅

## 最终结论
- **重叠根因**: isBlocked 检查与 setBlock 设置之间不是原子操作（check-then-act 竞态）
- **修复方案**: 位置预约锁 (Redis SET NX EX)，移动前先预约目标格子
- **死锁避免**: 只锁一个外部位置，TTL 5秒自动过期，不引入循环等待
- **编译**: ✅ BUILD SUCCESS
- **测试**: ✅ 纯单元 16/16, 集成测试需 Redis+RabbitMQ
