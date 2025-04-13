import IconFont from '../../components/IconFont';
import { getTextWidth, groupByColumn, isMobile } from '../../utils/utils';
import { AutoComplete, Select, Tag, Button, Upload, Image, message } from 'antd';
import classNames from 'classnames';
import { debounce } from 'lodash';
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import type { ForwardRefRenderFunction } from 'react';
import { SemanticTypeEnum, SEMANTIC_TYPE_MAP, HOLDER_TAG } from '../constants';
import { AgentType, ModelType } from '../type';
import { searchRecommend ,uploadAndParse, fileStatus } from '../../service';
import styles from './style.module.less';
import { useComposing } from '../../hooks/useComposing';
import type { GetProp, UploadFile, UploadProps } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';
import {
  UploadOutlined
} from '@ant-design/icons';


type FileType = Parameters<GetProp<UploadProps, 'beforeUpload'>>[0];

type Props = {
  inputMsg: string;
  chatId?: number;
  currentAgent?: AgentType;
  agentList: AgentType[];
  onToggleHistoryVisible: () => void;
  onOpenAgents: () => void;
  onInputMsgChange: (value: string) => void;
  onSendMsg: (msg: string, dataSetId?: number) => void;
  onAddConversation: (agent?: AgentType) => void;
  onSelectAgent: (agent: AgentType) => void;
  onOpenShowcase: () => void;
  onFileResultChange: (result: {fileContent:string,fileId:string,fileName:string}|undefined) => void;
};

const { OptGroup, Option } = Select;
let isPinyin = false;
let isSelect = false;

const compositionStartEvent = () => {
  isPinyin = true;
};

const compositionEndEvent = () => {
  isPinyin = false;
};

const ChatFooter: ForwardRefRenderFunction<any, Props> = (
  {
    inputMsg,
    chatId,
    currentAgent,
    agentList,
    onToggleHistoryVisible,
    onOpenAgents,
    onInputMsgChange,
    onSendMsg,
    onAddConversation,
    onSelectAgent,
    onOpenShowcase,
    onFileResultChange
  },
  ref
) => {
  const [modelOptions, setModelOptions] = useState<(ModelType | AgentType)[]>([]);
  const [stepOptions, setStepOptions] = useState<Record<string, any[]>>({});
  const [open, setOpen] = useState(false);
  const [focused, setFocused] = useState(false);
  const [previewImage, setPreviewImage] = useState('');
  const [previewOpen, setPreviewOpen] = useState(false);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const fileListRef = useRef<UploadFile[]>([]);
  const inputRef = useRef<any>();
  const fetchRef = useRef(0);
  const [fileResult, setFileResult] = useState<{fileContent:string,fileId:string,fileName:string}|undefined>(undefined);
  const [fileLoading, setFileLoading] = useState<boolean>(false)
  const [messageApi, contextHolder] = message.useMessage();
 
  const getBase64 = (file: FileType): Promise<string> =>
    new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.readAsDataURL(file);
      reader.onload = () => resolve(reader.result as string);
      reader.onerror = (error) => reject(error);
    });

  const handlePreview = async (file: UploadFile) => {
    if (!file.url && !file.preview) {
      file.preview = await getBase64(file.originFileObj as FileType);
    }
    setPreviewImage(file.url || (file.preview as string));
    setPreviewOpen(true);
  };

  const inputFocus = () => {
    inputRef.current?.focus();
  };

  const inputBlur = () => {
    inputRef.current?.blur();
  };

  useImperativeHandle(ref, () => ({
    inputFocus,
    inputBlur,
  }));

  const initEvents = () => {
    const autoCompleteEl = document.getElementById('chatInput');
    autoCompleteEl!.addEventListener('compositionstart', compositionStartEvent);
    autoCompleteEl!.addEventListener('compositionend', compositionEndEvent);
  };

  const removeEvents = () => {
    const autoCompleteEl = document.getElementById('chatInput');
    if (autoCompleteEl) {
      autoCompleteEl.removeEventListener('compositionstart', compositionStartEvent);
      autoCompleteEl.removeEventListener('compositionend', compositionEndEvent);
    }
  };

  useEffect(() => {
    initEvents();
    return () => {
      removeEvents();
    };
  }, []);

  const getStepOptions = (recommends: any[]) => {
    const data = groupByColumn(recommends, 'dataSetName');
    return isMobile && recommends.length > 6
      ? Object.keys(data)
          .slice(0, 4)
          .reduce((result, key) => {
            result[key] = data[key].slice(
              0,
              Object.keys(data).length > 2 ? 2 : Object.keys(data).length > 1 ? 3 : 6
            );
            return result;
          }, {})
      : data;
  };

  const processMsg = (msg: string) => {
    let msgValue = msg;
    let dataSetId: number | undefined;
    if (msg?.[0] === '/') {
      const agent = agentList.find(item => msg.includes(`/${item.name}`));
      msgValue = agent ? msg.replace(`/${agent.name}`, '') : msg;
    }
    return { msgValue, dataSetId };
  };

  const debounceGetWordsFunc = useCallback(() => {
    const getAssociateWords = async (msg: string, chatId?: number, currentAgent?: AgentType) => {
      if (isPinyin) {
        return;
      }
      if (msg === '' || (msg.length === 1 && msg[0] === '@')) {
        return;
      }
      fetchRef.current += 1;
      const fetchId = fetchRef.current;
      const { msgValue, dataSetId } = processMsg(msg);
      const res = await searchRecommend(msgValue.trim(), chatId, dataSetId, currentAgent?.id);
      if (fetchId !== fetchRef.current) {
        return;
      }
      const recommends = msgValue ? res.data || [] : [];
      const stepOptionList = recommends.map((item: any) => item.subRecommend);
      if (stepOptionList.length > 0 && stepOptionList.every((item: any) => item !== null)) {
        setStepOptions(getStepOptions(recommends));
      } else {
        setStepOptions({});
      }
      setOpen(recommends.length > 0);
    };
    return debounce(getAssociateWords, 200);
  }, []);

  const [debounceGetWords] = useState<any>(debounceGetWordsFunc);
  const saveFileResult = (result: {fileContent:string,fileId:string,fileName:string}|undefined,file?: UploadFile) => {
    if (file?.uid === fileListRef.current?.[0]?.uid || !result) {
      onFileResultChange(result);
      setFileResult(result);
      setFileLoading(false);
    }
  }
  useEffect(() => {
    fileListRef.current = fileList;
  }, [fileList]);

  useEffect(() => {
    setFileList([])
    setFileResult(undefined);
    setFileLoading(false);
  }, [chatId,currentAgent]);

  useEffect(() => {
    if (inputMsg.length === 1 && inputMsg[0] === '/') {
      setOpen(true);
      setModelOptions(agentList);
      setStepOptions({});
      return;
    }
    if (modelOptions.length > 0) {
      setTimeout(() => {
        setModelOptions([]);
      }, 50);
    }
    if (!isSelect) {
      debounceGetWords(inputMsg, chatId, currentAgent);
    } else {
      isSelect = false;
    }
    if (!inputMsg) {
      setStepOptions({});
      fetchRef.current = 0;
    }
  }, [inputMsg]);

  useEffect(() => {
    if (!focused) {
      setOpen(false);
    }
  }, [focused]);

  useEffect(() => {
    const autoCompleteDropdown = document.querySelector(
      `.${styles.autoCompleteDropdown}`
    ) as HTMLElement;
    if (!autoCompleteDropdown) {
      return;
    }
    const textWidth = getTextWidth(inputMsg);
    if (Object.keys(stepOptions).length > 0) {
      autoCompleteDropdown.style.marginLeft = `${textWidth}px`;
    } else {
      setTimeout(() => {
        autoCompleteDropdown.style.marginLeft = `0px`;
      }, 200);
    }
  }, [stepOptions]);

  const sendMsg = (value: string) => {
    const option = Object.keys(stepOptions)
      .reduce((result: any[], item) => {
        result = result.concat(stepOptions[item]);
        return result;
      }, [])
      .find(item =>
        Object.keys(stepOptions).length === 1
          ? item.recommend === value
          : `${item.dataSetName || ''}${item.recommend}` === value
      );

    if (option && isSelect) {
      onSendMsg(option.recommend, option.dataSetIds);
    } else {
      onSendMsg(value.trim(), option?.dataSetId);
    }
  };

  const autoCompleteDropdownClass = classNames(styles.autoCompleteDropdown, {
    [styles.mobile]: isMobile,
    [styles.modelOptions]: modelOptions.length > 0,
  });

  const onSelect = (value: string) => {
    isSelect = true;
    if (modelOptions.length > 0) {
      const agent = agentList.find(item => value.includes(item.name));
      if (agent) {
        if (agent.id !== currentAgent?.id) {
          onSelectAgent(agent);
        }
        onInputMsgChange('');
      }
    } else {
      onInputMsgChange(value.replace(HOLDER_TAG, ''));
    }
    setOpen(false);
    setTimeout(() => {
      isSelect = false;
    }, 200);
  };

  const chatFooterClass = classNames(styles.chatFooter, {
    [styles.mobile]: isMobile,
  });

  const modelOptionNodes = modelOptions.map(model => {
    return (
      <Option key={model.id} value={`/${model.name} `} className={styles.searchOption}>
        {model.name}
      </Option>
    );
  });

  const associateOptionNodes = Object.keys(stepOptions).map(key => {
    return (
      <OptGroup key={key} label={key}>
        {stepOptions[key].map(option => {
          let optionValue =
            Object.keys(stepOptions).length === 1
              ? option.recommend
              : `${option.dataSetName || ''}${option.recommend}`;
          if (inputMsg[0] === '/') {
            const agent = agentList.find(item => inputMsg.includes(item.name));
            optionValue = agent ? `/${agent.name} ${option.recommend}` : optionValue;
          }
          return (
            <Option
              key={`${option.recommend}${option.dataSetName ? `_${option.dataSetName}` : ''}`}
              value={`${optionValue}${HOLDER_TAG}`}
              className={styles.searchOption}
            >
              <div className={styles.optionContent}>
                {option.schemaElementType && (
                  <Tag
                    className={styles.semanticType}
                    color={
                      option.schemaElementType === SemanticTypeEnum.DIMENSION ||
                      option.schemaElementType === SemanticTypeEnum.MODEL
                        ? 'blue'
                        : option.schemaElementType === SemanticTypeEnum.VALUE
                        ? 'geekblue'
                        : 'cyan'
                    }
                  >
                    {SEMANTIC_TYPE_MAP[option.schemaElementType] ||
                      option.schemaElementType ||
                      '维度'}
                  </Tag>
                )}
                {option.subRecommend}
              </div>
            </Option>
          );
        })}
      </OptGroup>
    );
  });

  const fixWidthBug = () => {
    setTimeout(() => {
      const dropdownDom = document.querySelector(
        '.' + styles.autoCompleteDropdown + ' .rc-virtual-list-holder-inner'
      );

      if (!dropdownDom) {
        fixWidthBug();
      } else {
        // 获取popoverDom样式
        const popoverDomStyle = window.getComputedStyle(dropdownDom);
        // 在获取popoverDom中增加样式 width: fit-content
        dropdownDom.setAttribute('style', `${popoverDomStyle.cssText};width: fit-content`);
        // 获取popoverDom的宽度
        const popoverDomWidth = dropdownDom.clientWidth;
        // 将popoverDom的宽度赋值给他的父元素
        const offset = 20; // 预增加20px的宽度，预留空间给虚拟渲染出来的元素
        dropdownDom.parentElement!.style.width = popoverDomWidth + offset + 'px';
      }
    });
  };

  useEffect(() => {
    if (modelOptionNodes.length || associateOptionNodes.length) fixWidthBug();
  }, [modelOptionNodes.length, associateOptionNodes.length]);

  const { isComposing } = useComposing(document.getElementById('chatInput'));
  
  return (
    <div className={chatFooterClass}>
      {contextHolder}
      <div className={styles.tools}>
        <div
          className={styles.toolItem}
          onClick={() => {
            onAddConversation();
          }}
        >
          <IconFont type="icon-c003xiaoxiduihua" className={styles.toolIcon} />
          <div>新对话</div>
        </div>
        {!isMobile && (
          <div className={styles.toolItem} onClick={onToggleHistoryVisible}>
            <IconFont type="icon-lishi" className={styles.toolIcon} />
            <div>历史对话</div>
          </div>
        )}
        {agentList?.length > 1 && (
          <div className={styles.toolItem} onClick={onOpenAgents}>
            <IconFont type="icon-zhinengzhuli" className={styles.toolIcon} />
            <div>智能助理</div>
          </div>
        )}
        <div className={styles.toolItem} onClick={onOpenShowcase}>
          <IconFont type="icon-showcase" className={styles.toolIcon} />
          <div>showcase</div>
        </div>
      </div>
      <div className={styles.composer}>
        <div className={styles.composerInputWrapper}>
          <AutoComplete
            className={styles.composerInput}
            placeholder={
              currentAgent
                ? `【${currentAgent.name}】将与您对话，点击${!isMobile ? '左侧' : ''}【智能助理】${
                    !isMobile ? '列表' : ''
                  }可切换`
                : '请输入您的问题'
            }
            value={inputMsg}
            onChange={(value: string) => {
              onInputMsgChange(value);
            }}
            onSelect={onSelect}
            autoFocus={!isMobile}
            ref={inputRef}
            id="chatInput"
            onKeyDown={e => {
              if (e.code === 'Enter' || e.code === 'NumpadEnter') {
                const chatInputEl: any = document.getElementById('chatInput');
                const agent = agentList.find(
                  item => chatInputEl.value[0] === '/' && chatInputEl.value.includes(item.name)
                );
                if (agent) {
                  if (agent.id !== currentAgent?.id) {
                    onSelectAgent(agent);
                  }
                  onInputMsgChange('');
                  return;
                }
                if (!isSelect && !isComposing) {
                  sendMsg(chatInputEl.value);
                  setOpen(false);
                }
              }
            }}
            onFocus={() => {
              setFocused(true);
            }}
            onBlur={() => {
              setFocused(false);
            }}
            dropdownClassName={autoCompleteDropdownClass}
            listHeight={500}
            allowClear
            open={open}
            defaultActiveFirstOption={false}
            getPopupContainer={triggerNode => triggerNode.parentNode}
          >
            {modelOptions.length > 0 ? modelOptionNodes : associateOptionNodes}
          </AutoComplete>
          <div
            className={classNames(styles.sendBtn, {
              [styles.sendBtnActive]: (inputMsg?.length > 0 || fileResult) && !fileLoading,
            })}
            onClick={() => {
              if (inputMsg && !fileResult) {
                sendMsg(inputMsg);
              }else if (inputMsg && fileResult) {
                onSendMsg(`引用上文【文件[${fileResult?.fileName}]；文件id[${fileResult?.fileId}]】:\n\n`+inputMsg)
                setFileList([])
                setFileResult(undefined);
                setFileLoading(false);
              }else if (!inputMsg && fileResult) {
                onSendMsg(`引用上文【文件[${fileResult?.fileName}]；文件id[${fileResult?.fileId}]】:\n\n输出被引用的上文内容`)
                setFileList([])
                setFileResult(undefined);
                setFileLoading(false);
              }
              
            }}
          >
            <IconFont type="icon-ios-send" />
          </div>

          {/* 上传组件 */}
          <div
            className={styles.uploadContainer}
          >
            {fileLoading && <p><LoadingOutlined />&nbsp;&nbsp;解析中...</p>}
            <Upload
              maxCount={1}
              listType="picture-card"
              fileList={fileList}
              onChange = {({ file, fileList: newFileList }) => {
                // 这里只有删除的情况才会触发
                setFileList(newFileList);
                saveFileResult(undefined)
                console.log(file, 'file 2')
                console.log(newFileList, 'newFileList 2')
              }}
              onPreview={handlePreview}
            >
            </Upload>
            {previewImage && (
              <Image
                wrapperStyle={{ display: 'none' }}
                preview={{
                  visible: previewOpen,
                  onVisibleChange: (visible) => setPreviewOpen(visible),
                  afterOpenChange: (visible) => !visible && setPreviewImage(''),
                }}
                src={previewImage}
              />
            )}
          </div>
          {/* 上传组件按钮 */}
          <div className={styles.uploadHandler} style={{display:currentAgent?.chatAppConfig?.SMALL_TALK?.enable ? 'block' : 'none'}}>
            <Upload
              maxCount={1}
              fileList={fileList}
              showUploadList={false}
              onChange = {async ({ file, fileList: newFileList }) => {
                // 这里只有上传的情况才会触发
                setFileList(newFileList);
                console.log(file, 'file 1')
                console.log(newFileList, 'newFileList 1')
                if (file.status === 'done') {
                  setFileLoading(true)
                  try {
                    const parseRes = await uploadAndParse(file.originFileObj as File)
                    const taskId = parseRes?.data?.body?.resultList?.[0]?.taskId
                    if (taskId) {
                       let step = 0
                       const pollFileStatus = async () => {
                        try {
                          const res = await fileStatus({taskId})
                          if (res?.data?.body?.status === 'COMPLETED') {
                            saveFileResult({
                              fileContent: res.data.body.result?.fileContent,
                              fileId: res.data.body.result?.fileId,
                              fileName: file.name
                            }, file)
                          } else {
                            if (step < 20) {
                              step++
                              setTimeout(pollFileStatus, 500)
                            } else {
                              messageApi.error('文件解析失败');
                              setFileList([])
                              saveFileResult(undefined)
                              setFileLoading(false)
                            }
                          }
                        } catch (error) {
                          if (step < 20) {
                            step++
                            setTimeout(pollFileStatus, 100)
                          } else {
                            messageApi.error('文件解析失败');
                            setFileList([])
                            saveFileResult(undefined)
                            setFileLoading(false)
                          }
                        }
                       }
                       pollFileStatus()
                    }
                  } catch (error) {
                    messageApi.error(error as string);
                    setFileList([])
                    saveFileResult(undefined)
                    setFileLoading(false)
                  }
                }
              }}
            >
              <Button 
                type="primary" 
                className={styles.uploadHandlerBtn}
                icon={<UploadOutlined />}>
              </Button>
            </Upload>
          </div>

        </div>
      </div>
    </div>
  );
};

export default forwardRef(ChatFooter);
