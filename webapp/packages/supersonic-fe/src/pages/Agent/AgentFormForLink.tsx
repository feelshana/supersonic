import { AgentType, ModelType } from './type';
import AgentForm1 from './AgentForm1';
import { saveAgent, getAgentList, getModelList } from './service';
import { message, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import styles from './style.less';
const AgentFormForLink: React.FC = () => {
    const location = useLocation();
    const params = new URLSearchParams(location.search);
    const agentId = params.get('agentId');
    const [currentAgent, setCurrentAgent] = useState<AgentType>();
    const [modelId, setModelId] = useState<number>();
    const [domainId, setDomainId] = useState<number>();
    const [domainDataSetTree, setDomainDataSetTree] = useState<ModelType[]>([]);
    const [loading, setLoading] = useState(false);
    const updateData = async () => {
      setLoading(true);
      try {
        const res = await getAgentList();
        res.data.find((item) => {
          if (item.id === Number(agentId)) {
            document.title = `【${item.name}】小助手`
            console.log(item, 'item')
          }
          return item.id === Number(agentId) && setCurrentAgent(item);
        })   
      } finally {
        setLoading(false);
      }
    };
    const getModelListFunc = async () => {
      const { code, data } = await getModelList();
      if (code === 200) {
        setDomainDataSetTree(data);
      }
    }
    const onSaveAgent = async (agent: AgentType, noTip?: boolean) => {
      setLoading(true);
      try {
        const { data, code } = await saveAgent(agent);
        if (code === 200) {
          console.log(data)
        }
        if (!noTip) {
          message.success('保存成功');
        }
        updateData();
      } finally {
        setLoading(false);
      }
    };
    const onCreateToolBtnClick  = () => {};

    useEffect(() => {
      const header = document.querySelector('header')
      if(header?.style){
        header.style.display = 'none'
      }
      updateData();
      getModelListFunc();
    }, [agentId]);

    useEffect(() => {
      const modelId = currentAgent?.dataSetIds?.[0]
      setModelId(modelId)
    }, [currentAgent])

    useEffect(() => {
      if(modelId && domainDataSetTree.length) outer:{
        for (let i = 0; i < domainDataSetTree.length; i++) {
          for (let j = 0; j < domainDataSetTree[i].children!.length; j++) {
            if (domainDataSetTree[i].children![j].id === modelId) {
              setDomainId(domainDataSetTree[i].children![j].parentId)
              break outer
            }
          }
        }
      }
    }, [modelId, domainDataSetTree])

  return (
    <div className={styles.agentFormForLink}>
      {
        loading && <div className={styles.mask}>
          <Spin spinning={loading} tip="Loading..." size="large" />
        </div>
      }
      <AgentForm1
        onSaveAgent={onSaveAgent}
        editAgent={currentAgent}
        modelId={modelId}
        domainId={domainId}
        onCreateToolBtnClick={onCreateToolBtnClick}
      />
    </div>
  );
};

export default AgentFormForLink;
