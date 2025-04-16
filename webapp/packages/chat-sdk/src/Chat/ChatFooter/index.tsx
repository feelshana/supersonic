import IconFont from '../../components/IconFont';
import { getTextWidth, groupByColumn, isMobile } from '../../utils/utils';
import { AutoComplete, Select, Tag, Button, Upload, Image, message } from 'antd';
import classNames from 'classnames';
import { debounce } from 'lodash';
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import type { ForwardRefRenderFunction } from 'react';
import { SemanticTypeEnum, SEMANTIC_TYPE_MAP, HOLDER_TAG } from '../constants';
import { AgentType, ModelType, FileResultsType } from '../type';
import { searchRecommend ,uploadAndParse, fileStatus } from '../../service';
import styles from './style.module.less';
import { useComposing } from '../../hooks/useComposing';
import type { GetProp, UploadFile, UploadProps } from 'antd';
import { LoadingOutlined, CloseCircleOutlined,UploadOutlined } from '@ant-design/icons';


type FileType = Parameters<GetProp<UploadProps, 'beforeUpload'>>[0];

type Props = {
  inputMsg: string;
  chatId?: number;
  currentAgent?: AgentType;
  agentList: AgentType[];
  onToggleHistoryVisible: () => void;
  onOpenAgents: () => void;
  onInputMsgChange: (value: string) => void;
  onSendMsg: (msg: string, dataSetId?: number, fileResultsForReqStream?: FileResultsType) => void;
  onAddConversation: (agent?: AgentType) => void;
  onSelectAgent: (agent: AgentType) => void;
  onOpenShowcase: () => void;
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
  },
  ref
) => {
  const [modelOptions, setModelOptions] = useState<(ModelType | AgentType)[]>([]);
  const [stepOptions, setStepOptions] = useState<Record<string, any[]>>({});
  const [open, setOpen] = useState(false);
  const [focused, setFocused] = useState(false);
  const [previewImage, setPreviewImage] = useState('');
  const [previewOpen, setPreviewOpen] = useState(false);
  const inputRef = useRef<any>();
  const fetchRef = useRef(0);
  /* 文件上传相关状态 ---start */

  // 上传的文件列表，即文件上传组件里的fileList
  const [fileList, setFileList] = useState<UploadFile[]>([]); 
  const fileListRef = useRef<UploadFile[]>([]);

  // 【文件解析结果列表】该列表是已经解析完毕的解析结果
  const [fileResults, setFileResults] = useState<FileResultsType>([]);

  // 正在解析的文件uid列表, 解析完的文件uid会从该列表移除
  const [fileUidsInProgress, setFileUidsInProgress] = useState<string[]>([]) 
  const [messageApi, contextHolder] = message.useMessage();

  /* 文件上传相关状态 ---end */

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

  /* 文件上传相关功能 ---start */
  const getBase64 = (file: FileType): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.readAsDataURL(file);
      reader.onload = () => resolve(reader.result as string);
      reader.onerror = (error) => reject(error);
    });
  }

  const formatSize = (size) => {
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(2)} KB`;
    return `${(size / (1024 * 1024)).toFixed(2)} MB`;
  }
  
  /**
   * 动态改变【文件解析结果列表】
   * @param result 
   * 需要被操作的解析结果对象
   * 如果 !result, 则表示该操作并没有解析结果比如：文件被删除
   * @param file 
   * 需要被操作的文件对象
   * 如果 !file, 则表示这是一个清空文件上传状态的操作，通常是切换助手和新对话时使用
   * @returns 
   */
  const saveFileResult = (
    result: {
      fileContent:string,
      fileId:string,
      fileName:string,
      fileUid:string,
      fileSize:string,
      fileType:string
    }|undefined,
    file?: UploadFile
  ) => {
    if(!file) {
      setFileList([])
      setFileResults((prev)=>{
        return []
      });
      setFileUidsInProgress([])
      return
    }
    if(file?.status === 'removed') {
      setFileResults(prev => {
        prev = prev||[]
        const arr = prev.filter(item=>item.fileUid !== file?.uid)
        return arr
      })
      return
    }
    if (file && result) {
      setFileResults(prev => {
        prev = prev||[]
        let arr
        if(file.status === 'done'){
          const isIn = fileListRef.current.some((item)=>{return item.uid === result.fileUid})
          if(isIn) {
            arr = [...prev,result]
          }
        }
        return arr
      });
      setFileUidsInProgress(prev => {
        return prev.filter(item=>item!== result.fileUid)
      })
    }
  } 
  
  const sendMsgWithFile = () => {
    let str = '以下文件已解析后标记了文件id放入了上下文中，你可以在上下文中找到文件的完整解析内容，文件后跟的提问均是针对解析内容的提问。\n'
    fileResults && fileResults.forEach(
      (item: {fileContent:string,fileId:string,fileName:string,fileUid:string,fileSize:string,fileType:string}) => {
        str += `文件[${item.fileName}] 文件id[${item.fileId}] 文件大小[${item.fileSize}] 文件类型[${item.fileType}];\n`
      })
      if (inputMsg && !fileResults?.length && !fileUidsInProgress?.length) {
        sendMsg(inputMsg);
      }else if (inputMsg && fileResults?.length > 0 && !fileUidsInProgress?.length) {
        onSendMsg(str + inputMsg,undefined,JSON.parse(JSON.stringify(fileResults)))
        setFileList([])
        setFileResults([]);
        setFileUidsInProgress([])
      }else if (!inputMsg && fileResults?.length > 0 && !fileUidsInProgress?.length) {
        onSendMsg(str + '原封不动输出以上文件的解析内容',undefined,JSON.parse(JSON.stringify(fileResults)))
        setFileList([])
        setFileResults([]);
        setFileUidsInProgress([])
    }
  }
  
  useEffect(() => {
    fileListRef.current = fileList;
  }, [fileList])

  useEffect(() => {
    saveFileResult(undefined)
  }, [chatId,currentAgent])
  /* 文件上传相关功能 ---end */

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
                if ((inputMsg?.length > 0 || fileResults?.length > 0) && !fileUidsInProgress?.length) {
                  if (!isSelect && !isComposing) {
                    sendMsgWithFile()
                    setOpen(false);
                  }
                  
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
            getPopupContainer={triggerNode => triggerNode.parentNode}>
            {modelOptions.length > 0 ? modelOptionNodes : associateOptionNodes}
          </AutoComplete>
          <div
            className={classNames(styles.sendBtn, {
              [styles.sendBtnActive]: (inputMsg?.length > 0 || fileResults?.length > 0) && !fileUidsInProgress?.length,
            })}
            onClick={() => {
              sendMsgWithFile()
            }}>
            <IconFont type="icon-ios-send" />
          </div>
          {/* 上传组件 */}
          <div className={styles.uploadContainer}>
            {fileList.length>0 ? <div className={styles.uploadTip}>只识别文件中的文字</div> : ''}
            <Upload
              // 因为并没有真正上传没有action，但有默认行为所以这里method要设置为get
              method='get'
              maxCount={10}
              // listType="picture"
              fileList={fileList}
              itemRender={(originNode, file, fileList, actions) => (
                <div className={styles.fileItem}>
                  <div className={styles.fileIcon}>
                    {
                      file.type?.startsWith('image/') ? 
                      <img src={file.thumbUrl} alt="" onClick={()=>{actions.preview()}}/> : 
                      <span style={{fontSize:'24px'}}>&nbsp;📄&nbsp;&nbsp;</span>
                    }
                  </div>
                  <div className={styles.fileInfo}>
                    <div className={styles.fileName}>{file.name}</div>
                    <div className={styles.fileSize}>
                      {fileUidsInProgress.includes(file.uid) ? 
                        <div className={styles.loadingsItem}><LoadingOutlined />&nbsp;&nbsp;解析中...</div> : 
                        file?.type?.split('/')[0].toUpperCase() + ' ' + formatSize(file.size)
                      }
                    </div>
                  </div>
                  <div className={styles.closeButton}>
                    <CloseCircleOutlined 
                      className={styles.closeIcon}
                      onClick={() => {
                        actions.remove()
                      }}
                    />
                  </div>
                </div>
              )}
              onChange = {({ file, fileList: newFileList }) => {
                // 这里只有删除的情况才会触发
                setFileList(newFileList);
                saveFileResult(undefined, file)
                console.log(file, 'file 2')
                console.log(newFileList, 'newFileList 2')
                if (file.thumbUrl) URL.revokeObjectURL(file.thumbUrl);
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
              // 因为并没有真正上传没有action，但有默认行为所以这里method要设置为get
              method='get'
              maxCount={10}
              fileList={fileList}
              showUploadList={false}
              onChange = {async ({ file, fileList: newFileList }) => {
                // 这里只有上传的情况才会触发
                setFileList(newFileList);
                console.log(file, 'file 1')
                console.log(newFileList, 'newFileList 1')
                if (file.status === 'done') {
                  if(file.type?.startsWith('image/')){
                    file.thumbUrl = URL.createObjectURL(file.originFileObj as File)
                  }
                  setFileUidsInProgress((prev)=>{
                    return [...prev,file.uid]
                  })
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
                              fileName: file.name,
                              fileUid: file.uid,
                              fileSize: formatSize(file.size),
                              fileType: file.type?.split('/')[0].toUpperCase() || ''
                            },file)
                          } else {
                            if (step < 20) {
                              step++
                              setTimeout(pollFileStatus, 2000)
                            } else {
                              messageApi.error('文件解析失败');
                              saveFileResult(undefined, file)
                              setFileUidsInProgress((prev)=>{
                                return prev.filter(item=>item!== file.uid)
                              })
                            }
                          }
                        } catch (error) {
                          if (step < 20) {
                            step++
                            setTimeout(pollFileStatus, 100)
                          } else {
                            messageApi.error('文件解析失败');
                            saveFileResult(undefined, file)
                            setFileUidsInProgress((prev)=>{
                              return prev.filter(item=>item!== file.uid)
                            })
                          }
                        }
                       }
                       pollFileStatus()
                    }
                  } catch (error) {
                    messageApi.error('请求失败');
                    saveFileResult(undefined, file)
                    setFileUidsInProgress((prev)=>{
                      return prev.filter(item=>item!== file.uid)
                    })
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
