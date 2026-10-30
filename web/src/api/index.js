import http from './http'

// 所有后端接口集中在这里，页面只调用这些函数。
// 这样做的好处：接口路径只写一遍，后端改了路径只需要改这个文件；
// 页面里不出现字符串 '/api/exams/...'，搜起来也方便。

// ---------------- 认证 ----------------
export const login = (username, password) =>
  http.post('/auth/login', { username, password });

export const createUser = (payload) => http.post('/teacher/users', payload);

// ---------------- 学生端 ----------------
export const listAvailableExams = () => http.get('/exams/available');

// start 的返回里 status 可能是 ongoing / submitted / graded，
// 调用方必须按 status 决定进答题页还是成绩页 —— 重复进入返回的是原答卷。
export const startExam = (examId) => http.post(`/exams/${examId}/start`);

export const getPaper = (examId) => http.get(`/exams/${examId}/paper`);

export const saveAnswer = (recordId, questionId, answer) =>
  http.post(`/records/${recordId}/answers`, { questionId, answer });

export const submitExam = (recordId) => http.post(`/records/${recordId}/submit`);

// 注意：判分没完成时这个接口返回 400「还没判完，请稍后再看」，
// 调用方要把这个当成「正常的状态」而不是异常去弹错误框。
export const getResult = (recordId) => http.get(`/records/${recordId}/result`);

// ---------------- 老师端 ----------------
// 分页接口，返回 { items, total, page, size }（不是裸数组）。
// 不传 page/size 时后端给 1/20 —— 老师手上通常就几场考试，一页装得下。
export const listExams = (params) => http.get('/teacher/exams', { params });

export const createExam = (payload) => http.post('/teacher/exams', payload);

export const listQuestions = (params) => http.get('/teacher/questions', { params });

export const createQuestion = (payload) => http.post('/teacher/questions', payload);

// 改题是**整体覆盖**（PUT），不是部分更新。前端本来就把整张表单提交上来，
// 用 PATCH 语义反而要判断"哪个字段没传"，而这里的"没传"是有含义的
// （判断题不传 options、不传 score 表示用默认值）。
export const updateQuestion = (id, payload) => http.put(`/teacher/questions/${id}`, payload);

// 删题。被任何试卷引用时后端会拒绝并说明在哪份卷子里 —— 那句错误信息
// 要原样给到用户，不要改写成"删除失败"。
export const deleteQuestion = (id) => http.delete(`/teacher/questions/${id}`);

// 批量导入。dryRun 默认 true（后端也是这个默认值）——
// 预览返回的报告和真导入是同一个结构，只有 inserted 的含义从
// 「将会入库」变成「已入库」。调这个接口的页面必须先把报告摆给人看，
// 再让人点第二次「确认导入」，不能一次点完就写库。
export const importQuestions = (payload) => http.post('/teacher/questions/import', payload);

// 按配额自动组卷。quotas 形如 [{type:'single',difficulty:1,count:10}]，
// difficulty 传 null 表示不限难度；subject 不传就用这场考试的科目。
export const autoCompose = (examId, payload) =>
  http.post(`/teacher/exams/${examId}/auto-compose`, payload);

export const addQuestionsToExam = (examId, questionIds, score) =>
  http.post(`/teacher/exams/${examId}/questions`, { questionIds, score });

export const publishExam = (examId) => http.post(`/teacher/exams/${examId}/publish`);

export const closeExam = (examId) => http.post(`/teacher/exams/${examId}/close`);

export const getStats = (examId) => http.get(`/teacher/exams/${examId}/stats`);

export const getDistribution = (examId) => http.get(`/teacher/exams/${examId}/distribution`);

export const getAccuracy = (examId) => http.get(`/teacher/exams/${examId}/accuracy`);

export const getProgress = (examId) => http.get(`/teacher/exams/${examId}/progress`);
