package com.nedap.archie.diff;

import com.nedap.archie.aom.Archetype;
import com.nedap.archie.aom.CAttribute;
import com.nedap.archie.aom.CComplexObject;
import com.nedap.archie.aom.CObject;
import com.nedap.archie.aom.CPrimitiveObject;
import com.nedap.archie.aom.SiblingOrder;
import com.nedap.archie.aom.utils.AOMUtils;
import com.nedap.archie.rminfo.MetaModels;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nedap.archie.diff.DiffUtil.*;

/**
 * Determines sibling orders using a longest common subsequence-based diff algorithm
 */
public class LCSOrderingDiff {

    private final MetaModels metaModels;

    LCSOrderingDiff(MetaModels metaModels) {
        this.metaModels = metaModels;
    }

    public void addSiblingOrder(Archetype result, Archetype flatChild, Archetype flatParent) {
        addSiblingOrder(result.getDefinition(), flatChild.getDefinition(), flatParent.getDefinition());
    }

    private void addSiblingOrder(CAttribute resultAttribute, CAttribute flatChildAttribute, CAttribute flatParentAttribute) {
        for(CObject flatChildcObject:flatChildAttribute.getChildren()) {
            if(flatChildcObject instanceof CPrimitiveObject) {
                continue;
            }
            CObject flatParentcObject = findMatchingParentCObject(flatChildcObject.getNodeId(), flatParentAttribute.getChildren());
            CObject resultcObject = findMatchingParentCObject(flatChildcObject.getNodeId(), resultAttribute.getChildren());
            if(flatParentcObject != null) {
                //TODO: also slots and stuff
                if(flatParentcObject instanceof CComplexObject){
                    addSiblingOrder((CComplexObject) resultcObject, (CComplexObject) flatChildcObject, (CComplexObject) flatParentcObject);
                }
            }
        }

    }

    public void addSiblingOrder(CComplexObject result, CComplexObject flatChild, CComplexObject flatParent) {

        //TODO: check if the childAttribute is multiply-valued, and only then perform reordering
        //do however descend deeper in the tree, even for single valued attributes

        for(CAttribute flatChildAttribute:flatChild.getAttributes()) {
            CAttribute parentAttribute = getMatchingAttribute(flatParent, flatChildAttribute);

            if(parentAttribute == null || parentAttribute.getChildren().isEmpty()) {
                //this is a new attribute, so sibling order does not have to be done here
                //or it is an empty parent attribute, in which case we do not need sibling orders
                //or the child has only one node, in which case reordering is not necesarry
                continue;
            }

            CAttribute resultAttribute = getMatchingAttribute(result, flatChildAttribute);
            if(resultAttribute == null) {
                //attribute does not exist in result, but does in parent. Strange, but we cannot set sibling order. so continue
                continue;
            }
            //descend into children first
            addSiblingOrder(resultAttribute, flatChildAttribute, parentAttribute);

            if(!metaModels.isMultiple(parentAttribute.getParent().getRmTypeName(), parentAttribute.getRmAttributeName())){
                continue;
            }

            if(!metaModels.isOrdered(parentAttribute.getParent().getRmTypeName(), parentAttribute.getRmAttributeName())){
                continue;
            }

            if(flatChildAttribute.getChildren().size() > 1) {
                //step 1: determine sibling orders
                Map<SiblingOrder, List<CObject>> siblingOrders = createSiblingOrders(parentAttribute, flatChild, flatChildAttribute, resultAttribute);
                //step 2: apply sibling order to result archetype
                DiffUtil.addOrderToAttribute(siblingOrders);
            }
        }
    }

    private Map<SiblingOrder, List<CObject>> createSiblingOrders(CAttribute parentAttribute, CComplexObject flatChild, CAttribute flatChildAttribute, CAttribute resultAttribute) {
        Map<SiblingOrder, List<CObject>> siblingOrders = new LinkedHashMap<>();

        List<String> parentNodeIds = parentAttribute.getChildren().stream().map(cobject -> cobject.getNodeId()).collect(Collectors.toList());
        List<String> childNodeIds = flatChildAttribute.getChildren().stream().map(cobject -> cobject.getNodeId()).collect(Collectors.toList());
        int childSpecializationDepth = flatChild.getArchetype().specializationDepth();
        NodeIdLCS nodeIdLCS = new NodeIdLCS(parentNodeIds, childNodeIds, childSpecializationDepth);
        List<String> lcs = nodeIdLCS.getLCS();
        //TODO: get the replacements of nodes, when id4 is replaced by id4.1 they should be equal in the NodeIdsLcs
        //TODO: replace the lcs.contains method with contains the replacement of

        if(lcs.size() == 0) {
            //If there's no empty LCS, it's not possible to add sibling markers
        }  else {
            for (int i = 0; i < childNodeIds.size(); i++) {
                String nodeId = childNodeIds.get(i);
                if (!nodeIdLCS.contains(nodeId)) {
                    //not in the LCS, we may need to add a before/after marker

                    if(!handleDirectlyAfterSameParentNode(resultAttribute, siblingOrders, childNodeIds, childSpecializationDepth, i)) {
                        if(!handleAddAfterSiblingOrder(resultAttribute, siblingOrders, childNodeIds, nodeIdLCS, i)) {
                            addBeforeFirstLcsNodeOrder(resultAttribute, siblingOrders, lcs, nodeId);
                        }
                    }
                }
            }
        }
        return siblingOrders;
    }

    private void addBeforeFirstLcsNodeOrder(CAttribute resultAttribute, Map<SiblingOrder, List<CObject>> siblingOrders, List<String> lcs, String nodeId) {
        CObject cObjectInResult = resultAttribute.getChild(nodeId);
        DiffUtil.addSiblingOrder(siblingOrders, SiblingOrder.createBefore(lcs.get(0)), cObjectInResult);
    }

    /**
     * Handle all cases where a sibling order can be expressed as after[idX]
     *
     * @return
     */
    private boolean handleAddAfterSiblingOrder(CAttribute resultAttribute, Map<SiblingOrder, List<CObject>> siblingOrders, List<String> childNodeIds, NodeIdLCS nodeIdLCS, int i) {
        int childSpecializationDepth = resultAttribute.getArchetype().specializationDepth();
        String nodeId = childNodeIds.get(i);
        for (int j = i - 1; j >= 0; j--) {

            if (nodeIdLCS.contains(childNodeIds.get(j))) {
//TODO: the code below removes one additional sibling marker. It has bugs, so commented now
//                if(nodeIdLCS.isLast(childNodeIds.get(j)) &&
//                        AOMUtils.getSpecializationDepthFromCode(nodeId) == childSpecializationDepth &&
//                        !AOMUtils.codeExistsAtLevel(nodeId, childSpecializationDepth -1 )) {
//                    SiblingOrder after = SiblingOrder.createAfter(nodeIdLCS.getLCS().get(nodeIdLCS.getLCS().size() - 1));
//                    CObject cObjectInResult = resultAttribute.getChild(nodeId);
//                    List<CObject> cObjects = siblingOrders.get(after);
//                    if(cObjects != null) {
//                        //normally this can just be added at the end of the archetype. However, if something else is already explicitly at the end of the archetype, it will now be added after this
//                        //so manually mark it as really at the end
//                        DiffUtil.addSiblingOrder(siblingOrders, after, cObjectInResult);
//                    }
//                    return true;
//                }
                //TODO: add after childNodeIds[j] to sibling orders
                CObject cObjectInResult = resultAttribute.getChild(nodeId);
                DiffUtil.addSiblingOrder(siblingOrders, SiblingOrder.createAfter(childNodeIds.get(j)), cObjectInResult);
                return true;
            }
        }
        return false;
    }


    /**
     * Handle the special case:
     *
     * id3
     * id3.1
     * id3.2
     *
     * In that order. The sibling order of id3 needs to be copied to id3.1 + id3.2, or no sibling order is needed in case id3 has none
     * @param resultAttribute
     * @param siblingOrders
     * @param childNodeIds
     * @param childSpecializationDepth
     * @param i
     * @return
     */
    private boolean handleDirectlyAfterSameParentNode(CAttribute resultAttribute, Map<SiblingOrder, List<CObject>> siblingOrders, List<String> childNodeIds, int childSpecializationDepth, int i) {

        String nodeId = childNodeIds.get(i);

        boolean onlyTheSameParentNodeId = false;
        String firstNodeIdWithSameParent = null;

        for(int j = i - 1; j>= 0; j--) {

            String otherNodeId = childNodeIds.get(j);
            if(AOMUtils.getSpecializationDepthFromCode(nodeId) == childSpecializationDepth &&
                    AOMUtils.codeExistsAtLevel(nodeId, childSpecializationDepth-1) &&
                    AOMUtils.codeAtLevel(otherNodeId, childSpecializationDepth-1).equals(AOMUtils.codeAtLevel(nodeId, childSpecializationDepth-1))) {
                onlyTheSameParentNodeId = true;
                firstNodeIdWithSameParent = otherNodeId;
            } else {
                if (onlyTheSameParentNodeId) {
                    CObject cObjectInResult = resultAttribute.getChild(nodeId);
                    SiblingOrder order = DiffUtil.findSiblingOrder(siblingOrders, firstNodeIdWithSameParent);
                    if(order != null) {
                        DiffUtil.addSiblingOrder(siblingOrders, order, cObjectInResult);
                    }
                    return true;
                }
                return false;
            }

        }
        return false;
    }


}
