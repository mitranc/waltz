<script>

    import _ from "lodash";
    import {diagramService, hideEmptyCells, hoveredGroupId} from "../entity-diagram-store";
    import {flip} from 'svelte/animate';
    import CellContent from "../CellContent.svelte";
    import {
        mkContentBoxStyle,
        mkGroupCellStyle,
        mkChildGroupStyle,
        mkCellContentStyle,
        mkTitleStyle
    } from "../entity-diagram-utils";
    import {flattenChildren} from "../../../../common/hierarchy-utils";
    import Icon from "../../../../common/svelte/Icon.svelte";
    import {entity} from "../../../../common/services/enums/entity";

    export let group;

    const {selectedOverlay, selectedGroup, selectGroup, overlayData, clearSelectedGroup} = diagramService;

    function selectOverlayGroup() {
        if ($selectedGroup?.id === group.id) {
            clearSelectedGroup();
        } else {
            selectGroup(group);
        }
    }

    function hasData(node, dataById) {
        const childHasData = !_.isEmpty(dataById[node.id]);
        const childrenHaveData = _.some(flattenChildren(node), child => !_.isEmpty(dataById[child.id]));
        return childHasData || childrenHaveData;
    }

    $: children = $hideEmptyCells && $selectedOverlay
        ? _.filter(group?.children, child => hasData(child, $overlayData))
        : group?.children || [];

    $: overlayRequiresTitle = group?.data && $selectedOverlay?.showTitle;

    $: cellData = _.get($overlayData, group?.id);

    $: childKinds = _
        .chain(flattenChildren(group))
        .filter(d => !_.isEmpty(d.data))
        .map(d => d.data.entityReference.kind)
        .compact()
        .uniq()
        .value();

</script>

{#if group}
<div>
    <div style="display: flex">
        <div style={mkGroupCellStyle(group)}
             class="clickable"
             on:click|stopPropagation={selectOverlayGroup}
             on:keydown|stopPropagation={selectOverlayGroup}>
            {#if group.props.showTitle}
                <div style={mkTitleStyle(group, $selectedGroup?.id, $hoveredGroupId)}>
                    <button style="outline: none !important; width: 100%; background: none; border: none; color: inherit;">
                        {#if group.data}
                            <Icon name={_.get(entity, [group.data.entityReference.kind, "icon"], "info-circle")}/>
                        {:else}
                            {#each childKinds as childKind}
                                <span style="opacity: 0.5">
                                    <Icon name={_.get(entity, [childKind, "icon"], "info-circle")}/>
                                </span>
                            {/each}
                        {/if}
                        {group.title}
                    </button>
                </div>
            {:else if overlayRequiresTitle}
                <div style={mkTitleStyle(group, $selectedGroup?.id, $hoveredGroupId)}>
                    <button style="outline: none !important; width: 100%; background: none; border: none; color: inherit;">
                        {group.title}
                    </button>
                </div>
            {/if}

            <div style={mkContentBoxStyle(group)}>
                {#each _.orderBy(children, d => d.position) as child (child.id)}
                    <div style={mkChildGroupStyle(group, child)}
                         animate:flip="{{duration: 300}}">
                        <svelte:self group={child}>
                        </svelte:self>
                    </div>
                {:else}
                    {#if group.data}
                        <div style={mkCellContentStyle(group, $selectedGroup?.id, $hoveredGroupId)}>
                            <CellContent data={group.data}
                                         cellId={group.id}
                                         height={group.props.minWidth / 3}
                                         width={group.props.minWidth}/>
                        </div>
                    {/if}
                {/each}
            </div>
        </div>
    </div>
</div>
{/if}