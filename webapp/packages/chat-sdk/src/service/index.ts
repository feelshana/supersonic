import axios from './axiosInstance';
import {
  ChatContextType,
  HistoryMsgItemType,
  HistoryType,
  MsgDataType,
  ParseDataType,
  SearchRecommendItem,
} from '../common/type';
import type { DeepSeekStreamParams } from '../Chat/type'
import { isMobile } from '../utils/utils';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { getToken } from '../utils/utils';
const DEFAULT_CHAT_ID = 0;

const prefix = isMobile ? '/openapi' : '/api';

export function searchRecommend(
  queryText: string,
  chatId?: number,
  modelId?: number,
  agentId?: number
) {
  return axios.post<SearchRecommendItem[]>(`${prefix}/chat/query/search`, {
    queryText,
    chatId: chatId || DEFAULT_CHAT_ID,
    modelId,
    agentId,
  });
}

export function chatQuery(queryText: string, chatId?: number, modelId?: number, filters?: any[]) {
  return axios.post<MsgDataType>(`${prefix}/chat/query/query`, {
    queryText,
    chatId: chatId || DEFAULT_CHAT_ID,
    modelId,
    queryFilters: filters
      ? {
          filters,
        }
      : undefined,
  });
}

export function chatParse({
  queryText,
  chatId,
  modelId,
  agentId,
  parseId,
  queryId,
  filters,
  parseInfo,
}: {
  queryText: string;
  chatId?: number;
  modelId?: number;
  agentId?: number;
  queryId?: number;
  parseId?: number;
  filters?: any[];
  parseInfo?: ChatContextType;
}) {
  return axios.post<ParseDataType>(`${prefix}/chat/query/parse`, {
    queryText,
    chatId: chatId || DEFAULT_CHAT_ID,
    dataSetId: modelId,
    agentId,
    parseId,
    queryId,
    selectedParse: parseInfo,
    queryFilters: filters
      ? {
          filters,
        }
      : undefined,
  });
}

export function chatExecute(
  queryText: string,
  chatId: number,
  parseInfo: ChatContextType,
  agentId?: number
) {
  return axios.post<MsgDataType>(`${prefix}/chat/query/execute`, {
    queryText,
    agentId,
    chatId: chatId || DEFAULT_CHAT_ID,
    queryId: parseInfo.queryId,
    parseId: parseInfo.id,
  });
}

export function switchEntity(entityId: string, modelId?: number, chatId?: number) {
  return axios.post<any>(`${prefix}/chat/query/switchQuery`, {
    queryText: entityId,
    modelId,
    chatId: chatId || DEFAULT_CHAT_ID,
  });
}

export function queryData(chatContext: Partial<ChatContextType>) {
  return axios.post<MsgDataType>(`${prefix}/chat/query/queryData`, chatContext);
}

export function getHistoryMsg(
  current: number,
  chatId: number = DEFAULT_CHAT_ID,
  pageSize: number = 10
) {
  return axios.post<HistoryType>(`${prefix}/chat/manage/pageQueryInfo?chatId=${chatId}`, {
    current,
    pageSize,
  });
}

export function querySimilarQuestions(queryId: number) {
  return axios.get<HistoryMsgItemType>(`${prefix}/chat/manage/getChatQuery/${queryId}`);
}

export function deleteQuery(queryId: number) {
  return axios.delete<any>(`${prefix}/chat/manage/${queryId}`);
}

export function queryEntities(entityId: string | number, modelId: number) {
  return axios.post<any>(`${prefix}/chat/query/choice`, {
    entityId,
    modelId,
  });
}

export function updateQAFeedback(questionId: number, score: number) {
  return axios.post<any>(
    `${prefix}/chat/manage/updateQAFeedback?id=${questionId}&score=${score}&feedback=`
  );
}

export function queryDimensionValues(
  modelId: number,
  bizName: string,
  agentId: number,
  elementID: number,
  value: string
) {
  return axios.post<any>(`${prefix}/chat/query/queryDimensionValue`, {
    modelId,
    bizName,
    agentId,
    elementID,
    value,
  });
}

export function queryThoughtsInSSE(queryText: string, chatId: number | undefined, agentId: number | undefined, messageFunc: ((arg0: any) => void), errorFunc: ((arg0: any) => void), closeFunc: (() => void) ) {
  const ctrl = new AbortController();
  return fetchEventSource(`${prefix}/stream/chat/query/streamParse`, {
    method: 'POST',
    openWhenHidden: true, // 允许后台运行
    headers: {
      'Cache-Control': 'no-cache',
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + getToken()
    },
    body: JSON.stringify({
      queryText,
      chatId,
      agentId,
    }),
    signal: ctrl.signal,
    onopen: async (res) => {
      if (res.ok) {
        return;
      } else {
        errorFunc(new Error('连接不成功'))
        ctrl.abort();
        throw new Error('连接不成功')
      }
    },
    onmessage: messageFunc,
    onerror: (error) => {
      errorFunc(error)
      ctrl.abort();
      throw error
    },
    onclose: () => {
      closeFunc()
    }
  });
}
// 智能洞察
export function dataInterpret(
  textResult: string,
  queryText: string,
  chatId: number,
  parseInfo: ChatContextType,
  agentId?: number,
) {
  return axios.post<MsgDataType>(`${prefix}/chat/query/dataInterpret`, {
    textResult,
    queryText,
    agentId,
    chatId: chatId || DEFAULT_CHAT_ID,
    queryId: parseInfo.queryId,
    parseId: parseInfo.id,
  });
}
// 上传并解析文件
export function uploadAndParse(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return axios.post<any>(`${prefix}/chat/file/uploadAndParse`, formData );
}

// 文件解析状态查询,查询完成后会得到解析内容
export function fileStatus(params) {
  return axios.post<any>(`${prefix}/chat/file/status`, params);
}

// 【开启闲聊后】流式回答
export function deepSeekStream(
  {
    queryText,
    chatId,
    parseInfo,
    agentId,
    fileResultsForReqStream
  }:DeepSeekStreamParams,
  messageFunc: ((arg0: any) => void),
  errorFunc: ((arg0: any) => void),
  closeFunc: (() => void)
) {
const ctrl = new AbortController();
const bodyObj: {
  queryText: string;
  agentId: number | undefined;
  chatId: number;
  queryId: number | undefined;
  parseId: number;
  fileInfoList: {
    fileContent: string;
    fileId: string;
  }[]
} = {
  queryText,
  agentId,
  chatId: chatId || DEFAULT_CHAT_ID,
  queryId: parseInfo.queryId,
  parseId: parseInfo.id,
  fileInfoList: []
}
for (const fileResult of fileResultsForReqStream || []) {
  if (fileResult.fileId) {
    const newFileContent = `文件[${fileResult.fileName}] 文件id[${fileResult.fileId}] 文件大小[${fileResult.fileSize}] 文件类型[${fileResult.fileType}] 文件读取完成度[${fileResult.fileSizePercent}];以下内容为文件解析后的内容：\n`+fileResult.fileContent
    bodyObj.fileInfoList.push({fileContent:newFileContent,fileId:fileResult.fileId})
  }
}

return fetchEventSource(`${prefix}/stream/chat/crab/deepSeekStream`, {
  method: 'POST',
  openWhenHidden: true, // 允许后台运行
  headers: {
    'Cache-Control': 'no-cache',
    'Content-Type': 'application/json',
    'Authorization': 'Bearer ' + getToken()
  },
  body: JSON.stringify(bodyObj),
  signal: ctrl.signal,
  onopen: async (res) => {
    if (res.ok) {
      return;
    } else {
      errorFunc(new Error('连接不成功'))
      ctrl.abort();
      throw new Error('连接不成功')
    }
  },
  onmessage: messageFunc,
  onerror: (error) => {
    errorFunc(error)
    ctrl.abort();
    throw error
  },
  onclose: () => {
    closeFunc()
  }
})
}

// 【开启闲聊后】停止流式回答
export function stopStream(
  params:{
    queryId: number;
  }
) {
  return axios.post<any>(`${prefix}/chat/crab/stopStream`,params);
}